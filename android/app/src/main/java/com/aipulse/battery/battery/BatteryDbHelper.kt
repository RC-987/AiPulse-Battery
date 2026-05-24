package com.aipulse.battery.battery

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class BatteryDbHelper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    companion object {
        const val DB_NAME = "battery_monitor.db"
        // v5: app_usage.foreground_service_ms — enables accurate per-app
        // drain attribution for apps that drain primarily through a
        // persistent foreground service (the previous schema only persisted
        // UI foreground time, missing background-only drainers entirely).
        const val DB_VERSION = 5

        const val TABLE_SESSIONS = "sessions"
        const val TABLE_POINTS = "data_points"
        const val TABLE_APP_USAGE = "app_usage"
        // Long-horizon capacity/health timeline — one row per day max.
        // Lets us show capacity degradation trend across months.
        const val TABLE_CAPACITY_SNAPSHOTS = "capacity_snapshots"
        // Partial unique index: at most one active (end_time=0) session per type.
        // Catches double-insert races between BatteryWorker, ChargingReceiver, and getCurrentStats.
        private const val IDX_ACTIVE_TYPE_SQL =
            "CREATE UNIQUE INDEX IF NOT EXISTS idx_sessions_active_type " +
            "ON sessions(type) WHERE end_time = 0"
        // Speed up retention queries that select sessions by start_time.
        private const val IDX_SESSIONS_START_TIME_SQL =
            "CREATE INDEX IF NOT EXISTS idx_sessions_start_time ON sessions(start_time)"
        private const val IDX_POINTS_SESSION_SQL =
            "CREATE INDEX IF NOT EXISTS idx_points_session ON data_points(session_id)"
        private const val IDX_APPUSAGE_SESSION_SQL =
            "CREATE INDEX IF NOT EXISTS idx_appusage_session ON app_usage(session_id)"

        private const val CAPACITY_SNAPSHOTS_SQL = """
            CREATE TABLE IF NOT EXISTS capacity_snapshots (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                time INTEGER NOT NULL,
                actual_capacity_mah INTEGER NOT NULL,
                design_capacity_mah INTEGER DEFAULT 0,
                cycle_count INTEGER DEFAULT 0,
                health_pct REAL DEFAULT 0,
                nt_soh INTEGER DEFAULT -1,
                pct_when_taken INTEGER DEFAULT 0
            )
        """
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        // Enforce declared FOREIGN KEY constraints (off by default in Android SQLite).
        // deleteSession() already deletes children (app_usage, data_points) before the
        // parent session row, so this only adds protection against accidental orphans.
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE $TABLE_SESSIONS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                start_time INTEGER NOT NULL,
                end_time INTEGER DEFAULT 0,
                type TEXT DEFAULT 'charge'
            )
        """)
        db.execSQL("""
            CREATE TABLE $TABLE_POINTS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id INTEGER NOT NULL,
                time INTEGER NOT NULL,
                pct INTEGER NOT NULL,
                volt_mv INTEGER DEFAULT 0,
                current_ma INTEGER DEFAULT 0,
                charge_counter_uah INTEGER DEFAULT 0,
                temp_c REAL DEFAULT 0,
                FOREIGN KEY (session_id) REFERENCES $TABLE_SESSIONS(id)
            )
        """)
        db.execSQL("""
            CREATE TABLE $TABLE_APP_USAGE (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id INTEGER NOT NULL,
                package_name TEXT NOT NULL,
                app_name TEXT,
                foreground_ms INTEGER DEFAULT 0,
                foreground_service_ms INTEGER DEFAULT 0,
                background_ms INTEGER DEFAULT 0,
                snapshot_time INTEGER NOT NULL,
                start_pct INTEGER DEFAULT 0,
                end_pct INTEGER DEFAULT 0,
                FOREIGN KEY (session_id) REFERENCES $TABLE_SESSIONS(id)
            )
        """)
        db.execSQL(IDX_ACTIVE_TYPE_SQL)
        db.execSQL(IDX_SESSIONS_START_TIME_SQL)
        db.execSQL(IDX_POINTS_SESSION_SQL)
        db.execSQL(IDX_APPUSAGE_SESSION_SQL)
        db.execSQL(CAPACITY_SNAPSHOTS_SQL)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE $TABLE_SESSIONS ADD COLUMN type TEXT DEFAULT 'charge'")
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS $TABLE_APP_USAGE (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    session_id INTEGER NOT NULL,
                    package_name TEXT NOT NULL,
                    app_name TEXT,
                    foreground_ms INTEGER DEFAULT 0,
                    background_ms INTEGER DEFAULT 0,
                    snapshot_time INTEGER NOT NULL,
                    start_pct INTEGER DEFAULT 0,
                    end_pct INTEGER DEFAULT 0,
                    FOREIGN KEY (session_id) REFERENCES $TABLE_SESSIONS(id)
                )
            """)
        }
        if (oldVersion < 3) {
            // Pre-cleanup: if any duplicate active sessions of the same type exist,
            // close all but the most recent one (so the partial unique index can be created).
            db.execSQL("""
                UPDATE $TABLE_SESSIONS
                SET end_time = COALESCE(
                    (SELECT MAX(time) FROM $TABLE_POINTS WHERE session_id = $TABLE_SESSIONS.id),
                    start_time
                )
                WHERE end_time = 0
                  AND id NOT IN (
                    SELECT MAX(id) FROM $TABLE_SESSIONS WHERE end_time = 0 GROUP BY type
                  )
            """)
            db.execSQL(IDX_ACTIVE_TYPE_SQL)
        }
        if (oldVersion < 4) {
            db.execSQL(CAPACITY_SNAPSHOTS_SQL)
            db.execSQL(IDX_SESSIONS_START_TIME_SQL)
            db.execSQL(IDX_POINTS_SESSION_SQL)
            db.execSQL(IDX_APPUSAGE_SESSION_SQL)
        }
        if (oldVersion < 5) {
            // ALTER TABLE ADD COLUMN is safe and non-blocking on SQLite —
            // existing rows get the DEFAULT 0, which is the correct value
            // (we previously didn't track fgs at all).
            try {
                db.execSQL("ALTER TABLE $TABLE_APP_USAGE ADD COLUMN foreground_service_ms INTEGER DEFAULT 0")
            } catch (e: Exception) {
                // Column may already exist on a partially-migrated DB —
                // ignore so onUpgrade is idempotent.
            }
        }
    }

    /**
     * Insert a new session. Uses CONFLICT_IGNORE so a race against the partial
     * unique index (one active session per type) returns -1 instead of throwing.
     * Callers should handle -1 by re-fetching the existing active session.
     */
    fun startSession(type: String = "charge"): Long {
        val cv = ContentValues().apply {
            put("start_time", System.currentTimeMillis())
            put("type", type)
        }
        return writableDatabase.insertWithOnConflict(
            TABLE_SESSIONS, null, cv, SQLiteDatabase.CONFLICT_IGNORE
        )
    }

    fun endSession(sessionId: Long) {
        val cv = ContentValues().apply {
            put("end_time", System.currentTimeMillis())
        }
        writableDatabase.update(TABLE_SESSIONS, cv, "id=?", arrayOf(sessionId.toString()))
    }

    fun insertPoint(sessionId: Long, pct: Int, voltMv: Int, currentMa: Int, chargeUah: Int, tempC: Float) {
        val cv = ContentValues().apply {
            put("session_id", sessionId)
            put("time", System.currentTimeMillis())
            put("pct", pct)
            put("volt_mv", voltMv)
            put("current_ma", currentMa)
            put("charge_counter_uah", chargeUah)
            put("temp_c", tempC)
        }
        writableDatabase.insert(TABLE_POINTS, null, cv)
    }

    fun getPointsForSession(sessionId: Long): List<Map<String, Any>> {
        val points = mutableListOf<Map<String, Any>>()
        val cursor = readableDatabase.query(
            TABLE_POINTS, null,
            "session_id=?", arrayOf(sessionId.toString()),
            null, null, "time ASC"
        )
        cursor.use {
            while (it.moveToNext()) {
                points.add(mapOf(
                    "id" to it.getLong(it.getColumnIndexOrThrow("id")),
                    "time" to it.getLong(it.getColumnIndexOrThrow("time")),
                    "pct" to it.getInt(it.getColumnIndexOrThrow("pct")),
                    "voltMv" to it.getInt(it.getColumnIndexOrThrow("volt_mv")),
                    "currentMa" to it.getInt(it.getColumnIndexOrThrow("current_ma")),
                    "chargeCounterUah" to it.getInt(it.getColumnIndexOrThrow("charge_counter_uah")),
                    "tempC" to it.getFloat(it.getColumnIndexOrThrow("temp_c")),
                ))
            }
        }
        return points
    }

    fun getAllSessions(type: String? = null): List<Map<String, Any>> {
        val sessions = mutableListOf<Map<String, Any>>()
        val selection = type?.let { "type=?" }
        val args = type?.let { arrayOf(it) }
        val cursor = readableDatabase.query(
            TABLE_SESSIONS, null, selection, args, null, null, "start_time DESC"
        )
        cursor.use {
            while (it.moveToNext()) {
                val sid = it.getLong(it.getColumnIndexOrThrow("id"))
                val typeCol = try { it.getString(it.getColumnIndexOrThrow("type")) } catch (e: Exception) { "charge" }
                sessions.add(mapOf(
                    "id" to sid,
                    "startTime" to it.getLong(it.getColumnIndexOrThrow("start_time")),
                    "endTime" to it.getLong(it.getColumnIndexOrThrow("end_time")),
                    "type" to (typeCol ?: "charge"),
                ))
            }
        }
        return sessions
    }

    fun getAllSessionsWithStats(type: String? = null): List<Map<String, Any>> {
        val sessions = mutableListOf<Map<String, Any>>()
        val typeWhere = type?.let { "WHERE s.type = ?" } ?: ""
        val args = type?.let { arrayOf(it) } ?: emptyArray()
        val cursor = readableDatabase.rawQuery("""
            SELECT s.id, s.start_time, s.end_time, s.type,
                   COUNT(p.id) as point_count,
                   (SELECT pct FROM $TABLE_POINTS WHERE session_id = s.id ORDER BY time ASC LIMIT 1) as start_pct,
                   (SELECT pct FROM $TABLE_POINTS WHERE session_id = s.id ORDER BY time DESC LIMIT 1) as end_pct
            FROM $TABLE_SESSIONS s
            LEFT JOIN $TABLE_POINTS p ON p.session_id = s.id
            $typeWhere
            GROUP BY s.id
            ORDER BY s.start_time DESC
        """, args)
        cursor.use {
            while (it.moveToNext()) {
                sessions.add(mapOf(
                    "id" to it.getLong(0),
                    "startTime" to it.getLong(1),
                    "endTime" to it.getLong(2),
                    "type" to (it.getString(3) ?: "charge"),
                    "pointCount" to it.getInt(4),
                    "startPct" to (it.getIntOrNull(5) ?: 0),
                    "endPct" to (it.getIntOrNull(6) ?: 0),
                ))
            }
        }
        return sessions
    }

    private fun android.database.Cursor.getIntOrNull(index: Int): Int? {
        return if (isNull(index)) null else getInt(index)
    }

    fun getLatestActiveSessionId(type: String? = null): Long? {
        val selection = if (type != null) "end_time=0 AND type=?" else "end_time=0"
        val args = type?.let { arrayOf(it) }
        val cursor = readableDatabase.query(
            TABLE_SESSIONS, arrayOf("id"),
            selection, args, null, null, "start_time DESC", "1"
        )
        cursor.use {
            return if (it.moveToFirst()) it.getLong(0) else null
        }
    }

    /** Latest active session of `type` as Pair(id, startTime), or null. */
    fun getLatestActiveSession(type: String): Pair<Long, Long>? {
        val cursor = readableDatabase.query(
            TABLE_SESSIONS, arrayOf("id", "start_time"),
            "end_time=0 AND type=?", arrayOf(type),
            null, null, "start_time DESC", "1"
        )
        cursor.use {
            return if (it.moveToFirst()) Pair(it.getLong(0), it.getLong(1)) else null
        }
    }

    /** Timestamp of the most recent data point in this session, or null if none. */
    fun getLastPointTime(sessionId: Long): Long? {
        val cursor = readableDatabase.rawQuery(
            "SELECT MAX(time) FROM $TABLE_POINTS WHERE session_id=?",
            arrayOf(sessionId.toString())
        )
        cursor.use {
            return if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null
        }
    }

    /** End a session at a specific timestamp (used for force-cleanup of stale sessions). */
    fun endSessionAt(sessionId: Long, endTimeMs: Long) {
        val cv = ContentValues().apply { put("end_time", endTimeMs) }
        writableDatabase.update(TABLE_SESSIONS, cv, "id=?", arrayOf(sessionId.toString()))
    }

    fun getCapacityForSession(sessionId: Long): Int {
        val cursor = readableDatabase.rawQuery(
            "SELECT MAX(charge_counter_uah) - MIN(charge_counter_uah) FROM $TABLE_POINTS WHERE session_id=? AND charge_counter_uah > 0",
            arrayOf(sessionId.toString())
        )
        cursor.use {
            return if (it.moveToFirst()) (it.getInt(0) / 1000) else 0
        }
    }

    fun deleteSession(sessionId: Long) {
        // Transactional children-then-parent delete so a kill mid-way never
        // leaves orphan rows (FK constraint would otherwise block re-use).
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete(TABLE_APP_USAGE, "session_id=?", arrayOf(sessionId.toString()))
            db.delete(TABLE_POINTS, "session_id=?", arrayOf(sessionId.toString()))
            db.delete(TABLE_SESSIONS, "id=?", arrayOf(sessionId.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Delete sessions (and their children) whose **start_time** is older than
     * [olderThanMs]. Active sessions (end_time = 0) are never deleted regardless
     * of age — BatteryWorker.forceEndIfStale handles those via a different
     * mechanism. Returns the number of deleted parent sessions.
     *
     * Called periodically by BatteryWorker and on Battery-page mount to enforce
     * the user-requested 10-day retention policy. Runs inside a single
     * transaction so a kill mid-sweep leaves the DB consistent.
     */
    fun deleteOldData(olderThanMs: Long): Int {
        val db = writableDatabase
        db.beginTransaction()
        try {
            // Collect victim ids once; we delete children via a subquery to
            // avoid materialising the list twice.
            val idsSubquery =
                "SELECT id FROM $TABLE_SESSIONS WHERE start_time < ? AND end_time != 0"
            val args = arrayOf(olderThanMs.toString())
            db.execSQL(
                "DELETE FROM $TABLE_APP_USAGE WHERE session_id IN ($idsSubquery)",
                args
            )
            db.execSQL(
                "DELETE FROM $TABLE_POINTS WHERE session_id IN ($idsSubquery)",
                args
            )
            val deleted = db.delete(
                TABLE_SESSIONS, "start_time < ? AND end_time != 0", args
            )
            db.setTransactionSuccessful()
            return deleted
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Delete zero-signal sessions that are artifacts of plug-state flicker:
     *   - ended (end_time != 0)
     *   - older than [minAgeMs] (default: 1 h, so we don't touch live sessions)
     *   - <= 2 data points
     *   - first.pct == last.pct  (no real charge change)
     *
     * Returns the number of deleted sessions. Runs transactionally.
     */
    fun purgeMicroSessions(minAgeMs: Long = 3600_000L): Int {
        val db = writableDatabase
        val cutoff = System.currentTimeMillis() - minAgeMs
        db.beginTransaction()
        try {
            val victimIds = mutableListOf<Long>()
            val cursor = db.rawQuery("""
                SELECT s.id
                FROM $TABLE_SESSIONS s
                WHERE s.end_time != 0
                  AND s.end_time < ?
                  AND (
                    SELECT COUNT(*) FROM $TABLE_POINTS WHERE session_id = s.id
                  ) <= 2
                  AND (
                    SELECT IFNULL(MAX(pct), 0) - IFNULL(MIN(pct), 0)
                    FROM $TABLE_POINTS WHERE session_id = s.id
                  ) = 0
            """, arrayOf(cutoff.toString()))
            cursor.use { while (it.moveToNext()) victimIds.add(it.getLong(0)) }

            for (sid in victimIds) {
                val args = arrayOf(sid.toString())
                db.delete(TABLE_APP_USAGE, "session_id=?", args)
                db.delete(TABLE_POINTS, "session_id=?", args)
                db.delete(TABLE_SESSIONS, "id=?", args)
            }
            db.setTransactionSuccessful()
            return victimIds.size
        } finally {
            db.endTransaction()
        }
    }

    /** True if a capacity snapshot was already inserted today (local day). */
    fun hasCapacitySnapshotToday(): Boolean {
        val cal = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val startOfDay = cal.timeInMillis
        val cursor = readableDatabase.rawQuery(
            "SELECT 1 FROM $TABLE_CAPACITY_SNAPSHOTS WHERE time >= ? LIMIT 1",
            arrayOf(startOfDay.toString())
        )
        cursor.use { return it.moveToFirst() }
    }

    fun insertCapacitySnapshot(
        actualCapacityMah: Int,
        designCapacityMah: Int,
        cycleCount: Int,
        healthPct: Double,
        ntSoH: Int,
        pctWhenTaken: Int,
    ) {
        val cv = ContentValues().apply {
            put("time", System.currentTimeMillis())
            put("actual_capacity_mah", actualCapacityMah)
            put("design_capacity_mah", designCapacityMah)
            put("cycle_count", cycleCount)
            put("health_pct", healthPct)
            put("nt_soh", ntSoH)
            put("pct_when_taken", pctWhenTaken)
        }
        writableDatabase.insert(TABLE_CAPACITY_SNAPSHOTS, null, cv)
    }

    fun getCapacitySnapshots(sinceMs: Long = 0L, limit: Int = 365): List<Map<String, Any>> {
        val list = mutableListOf<Map<String, Any>>()
        val cursor = readableDatabase.rawQuery(
            """SELECT id, time, actual_capacity_mah, design_capacity_mah, cycle_count,
                      health_pct, nt_soh, pct_when_taken
               FROM $TABLE_CAPACITY_SNAPSHOTS
               WHERE time >= ?
               ORDER BY time ASC
               LIMIT ?""",
            arrayOf(sinceMs.toString(), limit.toString())
        )
        cursor.use {
            while (it.moveToNext()) {
                list.add(mapOf(
                    "id" to it.getLong(0),
                    "time" to it.getLong(1),
                    "actualCapacityMah" to it.getInt(2),
                    "designCapacityMah" to it.getInt(3),
                    "cycleCount" to it.getInt(4),
                    "healthPct" to it.getDouble(5),
                    "ntSoH" to it.getInt(6),
                    "pctWhenTaken" to it.getInt(7),
                ))
            }
        }
        return list
    }

    /** Reclaim space after a large delete — call sparingly (blocking write). */
    fun vacuum() {
        try { writableDatabase.execSQL("VACUUM") } catch (_: Exception) { }
    }

    /**
     * Heal existing sessions polluted by the pre-fix trailing-point bug:
     *
     *   - Discharge session whose **last** point is HIGHER than its second-to-last
     *     => tail-pollution (charge reading recorded after POWER_CONNECTED delay).
     *     Drop the last point.
     *   - Charge session whose **last** point is LOWER than its second-to-last
     *     => tail-pollution (discharge reading after POWER_DISCONNECTED delay).
     *     Drop the last point.
     *
     * Only applies to ENDED sessions (end_time != 0) so we don't modify the
     * currently-tracked session. Runs once and is safe to re-run (idempotent
     * after all tails have been removed).
     *
     * Returns the number of sessions healed.
     */
    fun healTailPollution(): Int {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val affected = mutableSetOf<Long>()
            // Iterate until no more anomalies are found. Bounded at 20 passes to
            // cap worst-case time on a badly-polluted DB — in practice 1-3 passes
            // cover everything (each pass is O(N sessions)).
            var totalDeleted = 0
            for (pass in 1..20) {
                val victims = mutableListOf<Pair<Long, Long>>()  // (sid, point_id)
                val cursor = db.rawQuery("""
                    SELECT s.id, p.id
                    FROM $TABLE_SESSIONS s
                    JOIN $TABLE_POINTS p ON p.session_id = s.id
                       AND p.time = (SELECT MAX(time) FROM $TABLE_POINTS WHERE session_id = s.id)
                    JOIN $TABLE_POINTS prev ON prev.session_id = s.id
                       AND prev.time = (
                         SELECT MAX(time) FROM $TABLE_POINTS
                         WHERE session_id = s.id AND time < p.time
                       )
                    WHERE s.end_time != 0
                      AND (
                        (s.type = 'discharge' AND p.pct > prev.pct)
                        OR (s.type = 'charge' AND p.pct < prev.pct)
                      )
                """, null)
                cursor.use {
                    while (it.moveToNext()) {
                        victims.add(Pair(it.getLong(0), it.getLong(1)))
                    }
                }
                if (victims.isEmpty()) break

                for ((sid, pid) in victims) {
                    db.delete(TABLE_POINTS, "id=?", arrayOf(pid.toString()))
                    affected.add(sid)
                }
                totalDeleted += victims.size
            }

            // Re-align each affected session's end_time to its NEW last point's time.
            for (sid in affected) {
                val newEnd = getLastPointTime(sid) ?: continue
                db.execSQL(
                    "UPDATE $TABLE_SESSIONS SET end_time=? WHERE id=? AND end_time != 0",
                    arrayOf(newEnd, sid)
                )
            }

            // Phase 2: delete *multi-plug-cycle* sessions that point-level healing
            // cannot fix. These are stuck sessions (often force-ended by
            // BatteryWorker.forceEndIfStale) whose data contains multiple plug
            // events interleaved. Signature:
            //   - charge session whose overall start_pct > end_pct, OR
            //   - discharge session whose overall start_pct < end_pct
            // Such sessions have middle-of-session reversals that can't be
            // split safely without more state. Drop them with their children.
            val unrecoverable = mutableListOf<Long>()
            val unrecCursor = db.rawQuery("""
                SELECT s.id, s.type,
                       (SELECT pct FROM $TABLE_POINTS WHERE session_id=s.id ORDER BY time ASC  LIMIT 1) AS start_pct,
                       (SELECT pct FROM $TABLE_POINTS WHERE session_id=s.id ORDER BY time DESC LIMIT 1) AS end_pct
                FROM $TABLE_SESSIONS s
                WHERE s.end_time != 0
            """, null)
            unrecCursor.use {
                while (it.moveToNext()) {
                    val sid = it.getLong(0)
                    val type = it.getString(1)
                    val sp = it.getInt(2)
                    val ep = it.getInt(3)
                    if (type == "charge" && ep < sp) unrecoverable.add(sid)
                    else if (type == "discharge" && ep > sp) unrecoverable.add(sid)
                }
            }
            for (sid in unrecoverable) {
                val args = arrayOf(sid.toString())
                db.delete(TABLE_APP_USAGE, "session_id=?", args)
                db.delete(TABLE_POINTS, "session_id=?", args)
                db.delete(TABLE_SESSIONS, "id=?", args)
            }

            db.setTransactionSuccessful()
            return affected.size + unrecoverable.size
        } finally {
            db.endTransaction()
        }
    }

    fun insertAppUsage(
        sessionId: Long, packageName: String, appName: String?,
        foregroundMs: Long, foregroundServiceMs: Long, backgroundMs: Long,
        startPct: Int, endPct: Int
    ) {
        val cv = ContentValues().apply {
            put("session_id", sessionId)
            put("package_name", packageName)
            put("app_name", appName)
            put("foreground_ms", foregroundMs)
            put("foreground_service_ms", foregroundServiceMs)
            put("background_ms", backgroundMs)
            put("snapshot_time", System.currentTimeMillis())
            put("start_pct", startPct)
            put("end_pct", endPct)
        }
        writableDatabase.insert(TABLE_APP_USAGE, null, cv)
    }

    fun getAppUsageForSession(sessionId: Long): List<Map<String, Any>> {
        val usage = mutableListOf<Map<String, Any>>()
        // Order by COMBINED active time (foreground + foreground service)
        // so background-only drainers surface to the top of the App Drain
        // list, matching the ranking shown in Android's native Battery
        // Usage screen.
        val cursor = readableDatabase.rawQuery(
            """SELECT package_name, app_name,
                      SUM(foreground_ms) as total_fg,
                      SUM(foreground_service_ms) as total_fgs,
                      SUM(background_ms) as total_bg,
                      MIN(start_pct) as min_pct, MAX(start_pct) as max_pct
               FROM $TABLE_APP_USAGE
               WHERE session_id=?
               GROUP BY package_name
               ORDER BY (total_fg + total_fgs) DESC""",
            arrayOf(sessionId.toString())
        )
        cursor.use {
            while (it.moveToNext()) {
                usage.add(mapOf(
                    "packageName" to it.getString(0),
                    "appName" to (it.getString(1) ?: it.getString(0)),
                    "foregroundMs" to it.getLong(2),
                    "foregroundServiceMs" to it.getLong(3),
                    "backgroundMs" to it.getLong(4),
                    "minPct" to it.getInt(5),
                    "maxPct" to it.getInt(6),
                ))
            }
        }
        return usage
    }

    fun getSessionType(sessionId: Long): String {
        val cursor = readableDatabase.query(
            TABLE_SESSIONS, arrayOf("type"),
            "id=?", arrayOf(sessionId.toString()),
            null, null, null
        )
        cursor.use {
            return if (it.moveToFirst()) (it.getString(0) ?: "charge") else "charge"
        }
    }
}
