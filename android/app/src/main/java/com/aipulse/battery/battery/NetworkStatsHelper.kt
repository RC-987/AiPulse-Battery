package com.aipulse.battery.battery

import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.net.ConnectivityManager
import android.util.Log

/**
 * Per-UID network usage over a time window.
 *
 * **Why this matters for battery drain attribution.** The system Battery
 * Usage screen combines many signals that are not exposed to non-system
 * apps — per-UID CPU time, wakelock durations, mobile radio active time,
 * sensor usage, etc. A non-privileged app can see none of those.
 *
 * What we CAN see with the existing `PACKAGE_USAGE_STATS` permission is
 * per-UID network bytes via [NetworkStatsManager]. Network activity is a
 * strong drain proxy — it implies radio wake-ups, CPU cycles spent on
 * serialisation, and in many cases push-driven work (FCM data messages,
 * sync adapters, scheduled jobs). Including it alongside foreground-UI
 * and foreground-service time closes most of the gap between AiPulse's
 * per-app ranking and the system Battery Usage ranking for apps that
 * drain primarily through background network activity.
 *
 * **Permission model.** `NetworkStatsManager.querySummary()` for UIDs
 * other than the caller's own requires `PACKAGE_USAGE_STATS` — which the
 * app already requests for foreground/service time. No new runtime
 * permission is introduced by this helper.
 *
 * **Historical data availability.** `NetworkStatsManager` keeps roughly
 * 30 days of history on a typical device. Our 10-day retention for
 * discharge sessions fits comfortably inside that window.
 */
class NetworkStatsHelper(private val context: Context) {

    companion object {
        private const val TAG = "NetworkStatsHelper"

        // ConnectivityManager.TYPE_MOBILE and TYPE_WIFI are the only network
        // transports that NetworkStatsManager.querySummary accepts as the
        // first argument. Bluetooth/ethernet/VPN are rolled up into one of
        // these by the framework or are irrelevant to phone battery drain.
        private val TRANSPORTS = intArrayOf(
            ConnectivityManager.TYPE_MOBILE,
            ConnectivityManager.TYPE_WIFI,
        )
    }

    /**
     * Aggregate bytes (rx + tx, mobile + wifi) per UID across [startMs, endMs].
     * Returns an empty map on any failure so callers can degrade gracefully —
     * network data is an *enhancement* signal, not a correctness requirement.
     *
     * One call per transport (mobile / wifi). Each call executes a binder
     * round-trip but the framework indexes by time, so the cost is bounded
     * by the number of UIDs that had activity in the window (typically a
     * few dozen on a real device).
     */
    fun getBytesPerUid(startMs: Long, endMs: Long): Map<Int, UidBytes> {
        if (endMs <= startMs) return emptyMap()
        val nsm = context.getSystemService(Context.NETWORK_STATS_SERVICE) as? NetworkStatsManager
            ?: return emptyMap()

        val result = HashMap<Int, UidBytes>()
        for (transport in TRANSPORTS) {
            try {
                // subscriberId must be null for apps targeting Q+ (they are
                // not allowed to read the IMSI). This matches the system
                // Battery Usage implementation.
                nsm.querySummary(transport, null, startMs, endMs).use { stats ->
                    val bucket = NetworkStats.Bucket()
                    while (stats.hasNextBucket()) {
                        stats.getNextBucket(bucket)
                        val uid = bucket.uid
                        // Skip kernel / system roll-up UIDs — they don't
                        // map to a user-installed app and their bytes are
                        // already attributed through other means.
                        if (uid < 0 || uid == NetworkStats.Bucket.UID_REMOVED ||
                            uid == NetworkStats.Bucket.UID_TETHERING) continue
                        val prev = result[uid]
                        val rx = (prev?.rxBytes ?: 0L) + bucket.rxBytes
                        val tx = (prev?.txBytes ?: 0L) + bucket.txBytes
                        val mobileRx = (prev?.mobileRxBytes ?: 0L) +
                            if (transport == ConnectivityManager.TYPE_MOBILE) bucket.rxBytes else 0L
                        val mobileTx = (prev?.mobileTxBytes ?: 0L) +
                            if (transport == ConnectivityManager.TYPE_MOBILE) bucket.txBytes else 0L
                        result[uid] = UidBytes(rx, tx, mobileRx, mobileTx)
                    }
                }
            } catch (e: SecurityException) {
                // Carrier / ROM variations occasionally deny NetworkStats
                // access even with PACKAGE_USAGE_STATS granted. Swallow the
                // error and return what we've collected so far; the caller
                // treats network data as optional.
                Log.w(TAG, "NetworkStats denied for transport=$transport: ${e.message}")
            } catch (e: Exception) {
                Log.w(TAG, "NetworkStats transport=$transport error", e)
            }
        }
        return result
    }

    /**
     * Totals for a single UID. Split-out mobile vs total so callers that
     * want to weight mobile traffic higher (radio drain >> wifi drain) can
     * do so; default `totalBytes` is rx+tx across all transports.
     */
    data class UidBytes(
        val rxBytes: Long = 0L,
        val txBytes: Long = 0L,
        val mobileRxBytes: Long = 0L,
        val mobileTxBytes: Long = 0L,
    ) {
        val totalBytes: Long get() = rxBytes + txBytes
        val mobileBytes: Long get() = mobileRxBytes + mobileTxBytes
    }
}
