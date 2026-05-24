package com.aipulse.battery.battery

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Unified 30-minute fallback worker for both charge and discharge sessions.
 * Safety net for missed BroadcastReceiver events on custom ROMs.
 * Does NOT start sessions — only records data points into existing ones.
 */
class BatteryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    companion object {
        const val TAG = "BatteryWorker"
        const val WORK_NAME = "battery_fallback_monitor"
        // Force-end any session whose start_time is older than this. Defense in depth
        // against stuck sessions (e.g. session 42 was active for 14 days). Set high
        // enough to accommodate legitimate long-idle scenarios (charge sessions in
        // your data hit 4.7 days when phone was left plugged in).
        private const val STALE_SESSION_THRESHOLD_MS = 7L * 24 * 60 * 60 * 1000L
        // User-requested retention window. Anything older gets deleted.
        private const val RETENTION_MS = 10L * 24 * 60 * 60 * 1000L
        // Run full retention sweep once per day — otherwise the worker would
        // fire every 30 min and do redundant work.
        private const val RETENTION_MIN_INTERVAL_MS = 20L * 60 * 60 * 1000L  // 20 h
        private const val PREFS_NAME = "battery_monitor_prefs"
        private const val KEY_LAST_RETENTION_MS = "last_retention_run_ms"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<BatteryWorker>(30, TimeUnit.MINUTES)
                .addTag(TAG)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
            Log.d(TAG, "30-min fallback worker scheduled")
        }
    }

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val db = BatteryDbHelper(ctx)

        val batteryIntent = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL

        // Defense in depth: force-end any session active longer than threshold.
        // Catches stuck sessions that escape the reconciliation logic below.
        forceEndIfStale(ctx, db, "charge")
        forceEndIfStale(ctx, db, "discharge")

        // Retention + hygiene sweeps (rate-limited to once per 20 h).
        maybeRunRetention(ctx, db)

        if (isCharging) {
            // Reconcile: end stale discharge session if any (missed POWER_CONNECTED broadcast)
            val staleDischargeSid = DischargeWorker.getActiveDischargeSessionId(ctx)
            if (staleDischargeSid > 0) {
                DischargeWorker.endDischargeSession(ctx)
                Log.d(TAG, "Reconciled stale discharge session $staleDischargeSid (now charging)")
            }

            // Record into active charge session
            val sid = ChargingReceiver.getActiveChargeSessionId(ctx)
            if (sid > 0) {
                ChargingReceiver.recordPoint(ctx, db, sid)
                Log.d(TAG, "Charge fallback point for session $sid")
            } else {
                // Missed POWER_CONNECTED broadcast — start session now
                ChargingReceiver.startChargeSession(ctx)
                Log.d(TAG, "Missed plug-in, started charge session")
            }
        } else {
            // Reconcile: end stale charge session if any (missed POWER_DISCONNECTED broadcast)
            val staleChargeSid = ChargingReceiver.getActiveChargeSessionId(ctx)
            if (staleChargeSid > 0) {
                ChargingReceiver.endChargeSession(ctx)
                Log.d(TAG, "Reconciled stale charge session $staleChargeSid (now discharging)")
            }

            // Record into active discharge session
            val sid = DischargeWorker.getActiveDischargeSessionId(ctx)
            if (sid > 0) {
                DischargeWorker.recordPoint(ctx, db, sid)
                Log.d(TAG, "Discharge fallback point for session $sid")
            } else {
                // Missed POWER_DISCONNECTED broadcast — start session now
                DischargeWorker.startDischargeSession(ctx)
                Log.d(TAG, "Missed unplug, started discharge session")
            }
        }

        return Result.success()
    }

    /**
     * Run retention + micro-session cleanup at most once every
     * [RETENTION_MIN_INTERVAL_MS]. Persists the last-run timestamp in
     * SharedPreferences. On first run after install, runs immediately.
     */
    private fun maybeRunRetention(ctx: Context, db: BatteryDbHelper) {
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val lastRun = prefs.getLong(KEY_LAST_RETENTION_MS, 0L)
        if (now - lastRun < RETENTION_MIN_INTERVAL_MS) return

        val cutoff = now - RETENTION_MS
        val deletedOld = db.deleteOldData(cutoff)
        val healed = db.healTailPollution()  // one-shot per sweep, idempotent
        val deletedMicro = db.purgeMicroSessions()
        prefs.edit().putLong(KEY_LAST_RETENTION_MS, now).apply()
        Log.d(TAG, "Retention sweep: old=$deletedOld healed=$healed micro=$deletedMicro (cutoff=${RETENTION_MS / 86_400_000L}d)")

        // VACUUM once per sweep reclaims space but blocks writes briefly —
        // only do it if we actually deleted something.
        if (deletedOld + deletedMicro + healed > 0) db.vacuum()
    }

    /**
     * Force-end an active session of the given type if it has been running longer
     * than [STALE_SESSION_THRESHOLD_MS]. Sets end_time to the last data point
     * timestamp (truthful 'session died at last update') instead of now, and
     * clears the matching SharedPreferences active-session id. Skips the usual
     * recordPoint / snapshotAppUsage to avoid attributing spurious data to the
     * stuck session.
     */
    private fun forceEndIfStale(ctx: Context, db: BatteryDbHelper, type: String) {
        val active = db.getLatestActiveSession(type) ?: return
        val (sid, startTime) = active
        val ageMs = System.currentTimeMillis() - startTime
        if (ageMs <= STALE_SESSION_THRESHOLD_MS) return

        val endAt = db.getLastPointTime(sid) ?: startTime
        db.endSessionAt(sid, endAt)
        val prefKey = if (type == "charge") "charge_session_id" else "discharge_session_id"
        ctx.getSharedPreferences("battery_monitor_prefs", Context.MODE_PRIVATE)
            .edit().putLong(prefKey, -1).apply()
        Log.w(TAG, "Force-ended stale $type session $sid (age=${ageMs / 3600_000L}h, endAt=$endAt)")
    }
}
