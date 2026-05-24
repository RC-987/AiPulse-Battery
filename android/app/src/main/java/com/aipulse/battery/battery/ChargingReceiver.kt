package com.aipulse.battery.battery

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log

/**
 * Event-driven battery monitoring.
 * Triggers on power connect/disconnect and battery-low — no polling.
 * Takes a snapshot data point, manages sessions, and schedules
 * a 30-min WorkManager fallback via BatteryWorker.
 */
class ChargingReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ChargingReceiver"
        private const val PREF_NAME = "battery_monitor_prefs"
        private const val KEY_CHARGE_SESSION = "charge_session_id"

        fun getActiveChargeSessionId(context: Context): Long {
            return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getLong(KEY_CHARGE_SESSION, -1)
        }

        fun startChargeSession(context: Context) {
            val db = BatteryDbHelper(context)
            val existing = db.getLatestActiveSessionId("charge")
            if (existing != null) {
                // Already active in DB — sync prefs in case they drifted, then exit
                context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                    .edit().putLong(KEY_CHARGE_SESSION, existing).apply()
                return
            }

            val sid = db.startSession("charge")
            if (sid <= 0) {
                // Lost a race against the partial unique index — another path inserted first.
                val winner = db.getLatestActiveSessionId("charge")
                if (winner != null) {
                    context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                        .edit().putLong(KEY_CHARGE_SESSION, winner).apply()
                    Log.w(TAG, "Charge session insert race; adopted existing $winner")
                }
                return
            }
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit().putLong(KEY_CHARGE_SESSION, sid).apply()

            recordPoint(context, db, sid)
            BatteryWorker.schedule(context)
            Log.d(TAG, "Charge session started: $sid")
        }

        /**
         * End the active charge session. **Does NOT record a trailing data point.**
         *
         * Rationale: POWER_DISCONNECTED (and the equivalent reconciliation path in
         * BatteryMonitorPlugin.getCurrentStats) can fire **after** the battery state
         * has already flipped to discharging — especially when the broadcast was
         * delayed by Doze / app-standby. A `recordPoint()` at that moment reads the
         * NEW (wrong) state and pollutes the session with a discharge-shaped point
         * on a charge session. Instead, we simply pin the end_time to the last
         * genuine in-state data point. If the session has no points at all (brief
         * plug cycle) we fall back to start_time so end_time > start_time.
         */
        fun endChargeSession(context: Context) {
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            val sid = prefs.getLong(KEY_CHARGE_SESSION, -1)
            if (sid > 0) {
                val db = BatteryDbHelper(context)
                val endAt = db.getLastPointTime(sid) ?: System.currentTimeMillis()
                db.endSessionAt(sid, endAt)
                prefs.edit().putLong(KEY_CHARGE_SESSION, -1).apply()
                Log.d(TAG, "Charge session ended: $sid (endAt=$endAt)")
            }
        }

        /** Record a single battery data point into the given session. */
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
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Action: ${intent.action}")
        when (intent.action) {
            Intent.ACTION_POWER_CONNECTED -> {
                // End any running discharge session with a final snapshot
                DischargeWorker.endDischargeSession(context)
                // Start a new charge session + first data point
                startChargeSession(context)
            }
            Intent.ACTION_POWER_DISCONNECTED -> {
                // End charge session with a final snapshot
                endChargeSession(context)
                // Start a new discharge session + baseline snapshot
                DischargeWorker.startDischargeSession(context)
            }
            Intent.ACTION_BATTERY_LOW -> {
                // Optional snapshot on battery-low event
                val db = BatteryDbHelper(context)
                val sid = DischargeWorker.getActiveDischargeSessionId(context)
                if (sid > 0) {
                    recordPoint(context, db, sid)
                    Log.d(TAG, "Battery-low snapshot for session $sid")
                }
            }
        }
    }
}
