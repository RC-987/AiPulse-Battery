package com.aipulse.battery.battery

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log

/**
 * Static helper for discharge session lifecycle.
 * No longer a WorkManager worker itself — BatteryWorker handles periodic fallback.
 */
object DischargeWorker {

    const val TAG = "DischargeWorker"
    private const val PREF_NAME = "battery_monitor_prefs"
    private const val KEY_DISCHARGE_SESSION = "discharge_session_id"
    private const val KEY_LAST_SNAPSHOT = "last_usage_snapshot"

    fun getActiveDischargeSessionId(context: Context): Long {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_DISCHARGE_SESSION, -1)
    }

    fun startDischargeSession(context: Context) {
        val db = BatteryDbHelper(context)
        val existing = db.getLatestActiveSessionId("discharge")
        if (existing != null) {
            // Already active in DB — sync prefs in case they drifted, then exit
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit().putLong(KEY_DISCHARGE_SESSION, existing).apply()
            return
        }

        val sid = db.startSession("discharge")
        if (sid <= 0) {
            // Lost a race against the partial unique index — another path inserted first.
            val winner = db.getLatestActiveSessionId("discharge")
            if (winner != null) {
                context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                    .edit().putLong(KEY_DISCHARGE_SESSION, winner).apply()
                Log.w(TAG, "Discharge session insert race; adopted existing $winner")
            }
            return
        }
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_DISCHARGE_SESSION, sid)
            .putLong(KEY_LAST_SNAPSHOT, System.currentTimeMillis())
            .remove("discharge_usage_baseline")  // legacy key, no longer used
            .apply()

        recordPoint(context, db, sid)

        BatteryWorker.schedule(context)
        Log.d(TAG, "Discharge session started: $sid")
    }

    /**
     * End the active discharge session. **Does NOT record a trailing data point.**
     *
     * Rationale: POWER_CONNECTED (and the equivalent reconciliation path in
     * BatteryMonitorPlugin.getCurrentStats) can fire **after** the battery state
     * has already flipped to charging — especially when the broadcast was
     * delayed by Doze / app-standby. A `recordPoint()` at that moment reads the
     * NEW (wrong) state (often a much higher pct after the phone has already
     * been charging for minutes) and pollutes the discharge session with a
     * charge-shaped tail point. Instead, pin end_time to the last genuine
     * in-state data point. The per-app usage snapshot is still taken because
     * UsageStatsManager queries reflect the full session window, not the
     * instantaneous battery state.
     */
    fun endDischargeSession(context: Context) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val sid = prefs.getLong(KEY_DISCHARGE_SESSION, -1)
        if (sid > 0) {
            val db = BatteryDbHelper(context)
            snapshotAppUsage(context, db, sid)
            val endAt = db.getLastPointTime(sid) ?: System.currentTimeMillis()
            db.endSessionAt(sid, endAt)
            prefs.edit()
                .putLong(KEY_DISCHARGE_SESSION, -1)
                .remove(KEY_LAST_SNAPSHOT)
                .apply()
            Log.d(TAG, "Discharge session ended: $sid (endAt=$endAt)")
        }
    }

    fun recordPoint(context: Context, db: BatteryDbHelper, sessionId: Long) {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val currentUa = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        val chargeUah = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)

        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val voltMv = batteryIntent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
        val tempTenths = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0

        db.insertPoint(sessionId, pct, voltMv, currentUa / 1000, chargeUah, tempTenths / 10f)
    }

    private fun snapshotAppUsage(context: Context, db: BatteryDbHelper, sessionId: Long) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val lastSnapshot = prefs.getLong(KEY_LAST_SNAPSHOT, System.currentTimeMillis() - 900_000)

        val tracker = AppUsageTracker(context)
        if (!tracker.hasPermission()) return

        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val currentPct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

        val apps = tracker.getUsageSince(lastSnapshot)
        for (app in apps) {
            // Persist whenever ANY of the three time buckets is non-zero.
            // foregroundServiceMs is the background-drain bucket sourced
            // from FOREGROUND_SERVICE_START/STOP events; including it here
            // ensures that background-only drainers (apps with little or no
            // UI time but a persistent foreground service) are not silently
            // dropped from the discharge session log.
            if (app.foregroundMs > 0 || app.foregroundServiceMs > 0 || app.backgroundMs > 0) {
                db.insertAppUsage(
                    sessionId, app.packageName, app.appName,
                    app.foregroundMs, app.foregroundServiceMs, app.backgroundMs,
                    currentPct, currentPct
                )
            }
        }

        prefs.edit().putLong(KEY_LAST_SNAPSHOT, System.currentTimeMillis()).apply()
    }
}
