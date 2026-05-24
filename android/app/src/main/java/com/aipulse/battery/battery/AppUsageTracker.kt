package com.aipulse.battery.battery

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.util.Log

class AppUsageTracker(private val context: Context) {

    companion object {
        private const val TAG = "AppUsageTracker"
        // Cache resolved labels for the lifetime of the process. The manifest
        // change (broad <queries> + QUERY_ALL_PACKAGES) makes
        // pm.getApplicationInfo() succeed for every package, but the call
        // still costs ~1ms per package. UsageStatsManager queries can return
        // 50+ unique packages in a 10-hour discharge session, so caching
        // matters once we re-resolve on every refresh.
        private val labelCache = HashMap<String, String>()

        // UsageEvents.Event constants for foreground services. Public API
        // since Android Q (API 29) but referenced via integer literals so
        // the source compiles cleanly without depending on hidden symbols.
        // Verified against AOSP/frameworks/base UsageEvents.java.
        private const val FOREGROUND_SERVICE_START = 19
        private const val FOREGROUND_SERVICE_STOP = 20

        // Pre-roll window prepended to every queryEvents call so the state
        // machine can see START events that bracket the user-requested
        // window. 24 h is enough for typical app usage patterns; longer
        // continuously-running services are caught by orphan-FGS-STOP
        // handling. Cost is negligible — queryEvents is indexed by time.
        private const val LOOKBACK_PREROLL_MS = 24L * 3600L * 1000L
    }

    private val usageStatsManager: UsageStatsManager
        get() = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

    /**
     * Resolve a human-readable label for [pkg]. Tries
     *   1. PackageManager.getApplicationLabel() — works for every package as
     *      long as the manifest grants visibility (Android 11+ requires
     *      <queries> or QUERY_ALL_PACKAGES).
     *   2. If the label equals the package name (some misconfigured apps),
     *      we still return it — better than nothing.
     *   3. Falls back to a *prettified* package suffix only when the package
     *      manager throws. The raw `substringAfterLast('.')` produced ugly
     *      lowercase slugs; we now Title-Case the suffix and drop a trailing
     *      "App" word so the worst case is a readable, capitalised string.
     */
    fun resolveAppLabel(pkg: String): String {
        labelCache[pkg]?.let { return it }
        val pm = context.packageManager
        val resolved = try {
            val ai = pm.getApplicationInfo(pkg, 0)
            val raw = pm.getApplicationLabel(ai).toString().trim()
            if (raw.isNotEmpty()) raw else prettifyPackageSuffix(pkg)
        } catch (e: PackageManager.NameNotFoundException) {
            // Visibility still denied → this means the manifest fix
            // didn't take effect (cached install? secondary user?). Log so
            // we can spot it in adb logcat.
            Log.w(TAG, "label fallback (NameNotFound) pkg=$pkg")
            prettifyPackageSuffix(pkg)
        } catch (e: Exception) {
            Log.w(TAG, "label fallback (" + e.javaClass.simpleName + ") pkg=$pkg", e)
            prettifyPackageSuffix(pkg)
        }
        labelCache[pkg] = resolved
        return resolved
    }

    /**
     * Last-resort name when PackageManager has no record of [pkg]. The raw
     * package suffix is too ugly to surface, so we Title-Case it and strip a
     * trailing "app" word for readability.
     */
    private fun prettifyPackageSuffix(pkg: String): String {
        val suffix = pkg.substringAfterLast('.').ifEmpty { pkg }
        // Split CamelCase / snake_case / kebab-case boundaries.
        val parts = suffix
            .replace(Regex("([a-z])([A-Z])"), "$1 $2")
            .split('_', '-', ' ')
            .filter { it.isNotEmpty() }
            .map { it.lowercase().replaceFirstChar { c -> c.titlecase() } }
        val joined = parts.joinToString(" ")
        // Strip a trailing redundant " App" word so labels read naturally.
        return joined.removeSuffix(" App").ifEmpty { suffix }
    }

    fun hasPermission(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * Per-package usage breakdown over a window. Two distinct buckets:
     *  - `foregroundMs`: time the package's UI activity was on top of the
     *    activity stack and visible to the user. Maps to MOVE_TO_FOREGROUND /
     *    MOVE_TO_BACKGROUND events. This is what the user perceives as
     *    "screen time".
     *  - `foregroundServiceMs`: time the package was running a foreground
     *    service — a background service with elevated priority backed by a
     *    persistent notification (sync apps, music players, navigation,
     *    fitness trackers, alarm / always-on apps). The UI is NOT in the
     *    foreground but the app is actively consuming CPU, network, and
     *    holding wakelocks. Maps to FOREGROUND_SERVICE_START /
     *    FOREGROUND_SERVICE_STOP (UsageEvents.Event types 19 / 20, public
     *    since API 29).
     *
     * Tracking both buckets is essential for accurate battery attribution:
     * an app with 1 minute of UI time but 8 hours of foreground-service
     * time is a major background drainer that the system Battery Usage
     * screen would rank near the top — and AiPulse must do the same.
     */
    data class PackageTime(
        val foregroundMs: Long = 0L,
        val foregroundServiceMs: Long = 0L,
    ) {
        val activeMs: Long get() = foregroundMs + foregroundServiceMs
    }

    /**
     * Compute per-package foreground & foreground-service time scoped to
     * the half-open interval [sinceMs, endMs).
     *
     * **Why not UsageStats.getTotalTimeInForeground / getTotalTimeForegroundServiceUsed?**
     * Both report bucket-aligned totals — for INTERVAL_DAILY that's the entire
     * calendar day, not the requested slice. That over-counts heavily for
     * sub-day windows.
     *
     * **Algorithm:** walk raw UsageEvents, run two parallel state machines
     * (foreground UI, foreground service) per package, sum closed intervals.
     *
     * **Pre-roll lookback.** A foreground service that started *before*
     * `sinceMs` and is still running emits no START event in our query
     * range, only (eventually) a STOP. To capture such cases we widen the
     * `queryEvents` range backward by [LOOKBACK_PREROLL_MS] and clip every
     * credited interval to [sinceMs, endMs] at the time of crediting. This
     * way pre-roll never inflates totals — it only helps us recognise
     * START events that bracket the window. Continuously-running services
     * older than the pre-roll are caught by orphan-STOP handling below.
     *
     * **Orphan handling differs by event class.** Activity events (FG) and
     * service events (FGS) have different invariants:
     *  - Activities can have multi-instance churn (sibling activities of the
     *    same package emit interleaved RESUMED/PAUSED within ms). Crediting
     *    an orphan PAUSED to `sinceMs` would over-count by an entire window.
     *    So we conservatively SKIP orphan PAUSEDs, accepting a small
     *    under-count for activities already foregrounded before `sinceMs`.
     *  - Foreground services have no multi-instance churn — START/STOP map
     *    1:1 to a single service lifecycle. An orphan FGS_STOP unambiguously
     *    means the service was already running at `sinceMs`. We credit the
     *    interval [sinceMs, stopTs) for orphan FGS_STOPs so long-running
     *    services that bracket the window are counted correctly.
     *
     * **Defensive clamp.** Per-package totals are capped at the window
     * length so no single package can ever exceed (endMs - sinceMs), even
     * in the face of clock drift or duplicate events.
     */
    fun getPackageTimeRange(sinceMs: Long, endMs: Long): Map<String, PackageTime> {
        if (!hasPermission() || endMs <= sinceMs) return emptyMap()
        val windowMs = endMs - sinceMs
        val queryStart = (sinceMs - LOOKBACK_PREROLL_MS).coerceAtLeast(0L)

        data class PkgEvent(val ts: Long, val type: Int)
        // type: 1 = FG_START, 2 = FG_STOP, 3 = FGS_START, 4 = FGS_STOP
        val byPkg = HashMap<String, ArrayList<PkgEvent>>()

        val events = usageStatsManager.queryEvents(queryStart, endMs)
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val pkg = event.packageName ?: continue
            val mappedType = when (event.eventType) {
                UsageEvents.Event.MOVE_TO_FOREGROUND -> 1   // ACTIVITY_RESUMED
                UsageEvents.Event.MOVE_TO_BACKGROUND -> 2   // ACTIVITY_PAUSED
                FOREGROUND_SERVICE_START -> 3
                FOREGROUND_SERVICE_STOP -> 4
                else -> continue
            }
            byPkg.getOrPut(pkg) { ArrayList() }.add(PkgEvent(event.timeStamp, mappedType))
        }

        val out = HashMap<String, PackageTime>(byPkg.size)
        for ((pkg, evs) in byPkg) {
            evs.sortBy { it.ts }
            var fgStart: Long? = null
            var fgsStart: Long? = null
            var fgTotal = 0L
            var fgsTotal = 0L

            // Credit the intersection of [s, e] with the user-requested
            // [sinceMs, endMs] window. This makes pre-roll lookback safe:
            // intervals that begin before the window only contribute the
            // portion that falls inside it.
            fun credit(start: Long, end: Long, isFgs: Boolean) {
                val a = start.coerceAtLeast(sinceMs)
                val b = end.coerceAtMost(endMs)
                val d = (b - a).coerceAtLeast(0L)
                if (d > 0) {
                    if (isFgs) fgsTotal += d else fgTotal += d
                }
            }

            for (e in evs) {
                when (e.type) {
                    1 -> if (fgStart == null) fgStart = e.ts                    // FG_START
                    2 -> {                                                       // FG_STOP
                        val s = fgStart
                        if (s != null) {
                            credit(s, e.ts, isFgs = false)
                            fgStart = null
                        }
                        // Orphan PAUSED for activity events: skipped to
                        // avoid multi-activity over-count (see KDoc).
                    }
                    3 -> if (fgsStart == null) fgsStart = e.ts                  // FGS_START
                    4 -> {                                                       // FGS_STOP
                        val s = fgsStart
                        if (s != null) {
                            credit(s, e.ts, isFgs = true)
                            fgsStart = null
                        } else {
                            // Orphan FGS_STOP: service was already running
                            // at queryStart. Credit from sinceMs up to the
                            // stop event so continuously-running services
                            // older than the pre-roll are still counted.
                            credit(sinceMs, e.ts, isFgs = true)
                        }
                    }
                }
            }
            // Still active at endMs? Close the open interval at the window edge.
            fgStart?.let { s -> credit(s, endMs, isFgs = false) }
            fgsStart?.let { s -> credit(s, endMs, isFgs = true) }

            // Defensive clamp: no package can ever exceed the window length.
            if (fgTotal > windowMs) fgTotal = windowMs
            if (fgsTotal > windowMs) fgsTotal = windowMs
            if (fgTotal > 0 || fgsTotal > 0) {
                out[pkg] = PackageTime(fgTotal, fgsTotal)
            }
        }
        return out
    }

    /**
     * Backwards-compatible helper that returns ONLY the foreground time map.
     * Kept for callers that don't yet need the foreground-service breakdown.
     */
    fun getForegroundTimeRange(sinceMs: Long, endMs: Long): Map<String, Long> =
        getPackageTimeRange(sinceMs, endMs).mapValues { it.value.foregroundMs }

    fun getUsageSince(sinceMs: Long): List<AppUsageInfo> {
        if (!hasPermission()) return emptyList()
        val now = System.currentTimeMillis()
        val map = getPackageTimeRange(sinceMs, now)
        if (map.isEmpty()) return emptyList()

        return map.asSequence()
            .filter { it.value.activeMs > 0 }
            .map { (pkg, t) ->
                AppUsageInfo(
                    packageName = pkg,
                    appName = resolveAppLabel(pkg),
                    foregroundMs = t.foregroundMs,
                    foregroundServiceMs = t.foregroundServiceMs,
                    backgroundMs = 0L,
                )
            }
            // Sort by *combined* active time so apps that drain the
            // battery primarily through a foreground service (with little
            // or no UI time) surface alongside heavy-UI apps.
            .sortedByDescending { it.foregroundMs + it.foregroundServiceMs }
            .take(30)
            .toList()
    }

    fun getAppName(packageName: String): String = resolveAppLabel(packageName)

    data class AppUsageInfo(
        val packageName: String,
        val appName: String,
        val foregroundMs: Long,
        val foregroundServiceMs: Long = 0L,
        val backgroundMs: Long,
    )
}
