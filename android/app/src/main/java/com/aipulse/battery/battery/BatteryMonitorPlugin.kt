package com.aipulse.battery.battery

import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import java.io.BufferedReader
import java.io.InputStreamReader

@CapacitorPlugin(name = "BatteryMonitor")
class BatteryMonitorPlugin : Plugin() {

    companion object {
        private const val PROP_CACHE_TTL_MS = 300_000L // 5 minutes
        private const val SOH_READ_INTERVAL_MS = 15L * 24 * 60 * 60 * 1000 // 15 days
        private const val PREFS_NAME = "battery_monitor_prefs"
        private const val KEY_NT_SOH = "nt_soh_value"
        private const val KEY_NT_SOH_TIME = "nt_soh_read_time"
        private const val KEY_LAST_RECORDED_POINT = "last_recorded_point_ms"
        private const val SMOOTHING_WINDOW = 6 // average last N readings (accumulates across app opens within same process)
        private const val RECORD_THROTTLE_MS = 120_000L // 2 minutes — prevent duplicate data points from rapid JS calls
    }

    // Rolling buffer for drain rate smoothing
    private val currentMaHistory = mutableListOf<Int>()

    private fun readCycleCount(bm: BatteryManager): Int {
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                val count = bm.getIntProperty(6) // BATTERY_PROPERTY_CYCLE_COUNT
                if (count > 0) return count
            } catch (_: Exception) {}
        }
        val sysPaths = listOf(
            "/sys/class/power_supply/battery/cycle_count",
            "/sys/class/power_supply/bms/cycle_count"
        )
        for (path in sysPaths) {
            try {
                val value = java.io.File(path).readText().trim().toIntOrNull()
                if (value != null && value > 0) return value
            } catch (_: Exception) { }
        }
        return getSystemProp("persist.sys.BatteryCycleCount").toIntOrNull() ?: 0
    }

    // Attempt to read battery design capacity from kernel or props.
    // Returns 0 if unknown (so UI can hide health % rather than show wrong value).
    private fun readDesignCapacityMah(): Int {
        // Try common kernel sysfs paths (works on many phones if readable)
        val sysPaths = listOf(
            "/sys/class/power_supply/battery/charge_full_design",
            "/sys/class/power_supply/Battery/charge_full_design",
            "/sys/class/power_supply/bms/charge_full_design"
        )
        for (path in sysPaths) {
            try {
                val value = java.io.File(path).readText().trim().toLongOrNull()
                if (value != null && value > 0) {
                    return (value / 1000).toInt() // μAh → mAh
                }
            } catch (_: Exception) { }
        }
        
        val energyPaths = listOf(
            "/sys/class/power_supply/battery/energy_full_design",
            "/sys/class/power_supply/bms/energy_full_design"
        )
        for (path in energyPaths) {
            try {
                val value = java.io.File(path).readText().trim().toLongOrNull()
                // Convert uWh to mAh assuming nominal voltage of 3.85V (3850000 uV)
                if (value != null && value > 0) {
                    return (value / 3850).toInt()
                }
            } catch (_: Exception) { }
        }

        // Known device hardcodes
        val model = android.os.Build.MODEL
        val product = android.os.Build.PRODUCT
        if (model == "A024" || product.startsWith("Metroid")) return 5500
        
        // Unknown — return 0 so caller can decide whether to show health
        return 0
    }

    private val dbHelper: BatteryDbHelper by lazy { BatteryDbHelper(context) }
    private val propCache = mutableMapOf<String, Pair<String, Long>>()

    private fun getPrefs() = context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)

    /**
     * Read Nothing's internal SoH from logcat, but only once every 15 days.
     * Returns cached value between reads. Requires READ_LOGS permission (granted via ADB).
     * Pattern: "NTStabilityGetBattHealthInfo:100mRealBatSoh:95"
     */
    private fun readNtSoH(): Int {
        val prefs = getPrefs()
        val lastRead = prefs.getLong(KEY_NT_SOH_TIME, 0)
        val cached = prefs.getInt(KEY_NT_SOH, -1)
        val now = System.currentTimeMillis()

        // Return cached value if within 15-day window
        if (cached > 0 && (now - lastRead) < SOH_READ_INTERVAL_MS) {
            return cached
        }

        // Try reading from logcat
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-s", "NtCharge:I"))
            val reader = java.io.BufferedReader(java.io.InputStreamReader(process.inputStream))
            var soh = -1
            reader.forEachLine { line ->
                val match = Regex("100mRealBatSoh:(\\d+)").find(line)
                if (match != null) {
                    soh = match.groupValues[1].toIntOrNull() ?: soh
                }
            }
            reader.close()
            process.waitFor()

            if (soh > 0) {
                prefs.edit()
                    .putInt(KEY_NT_SOH, soh)
                    .putLong(KEY_NT_SOH_TIME, now)
                    .apply()
            }
            if (soh > 0) soh else cached
        } catch (_: Exception) {
            if (cached > 0) cached else -1
        }
    }

    /**
     * Infer charging protocol from current charging parameters.
     * Nothing Phone 3 supports: PD3.0, PPS, UFCS, QC4, Qi wireless.
     * Protocol is determined by voltage/current negotiation:
     * - UFCS: typically 5-20V, up to 6.5A (chg_type:16 in kernel)
     * - PPS: 3.3-21V programmable, up to 5A
     * - PD: fixed voltage steps (5V, 9V, 15V, 20V)
     * - QC: 5V/9V/12V
     * - Qi: wireless, typically 5V-10V
     */
    private fun inferChargeProtocol(plugged: Int, maxCurrentUa: Int, maxVoltageUv: Int): String {
        if (plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS) {
            val watts = if (maxCurrentUa > 0 && maxVoltageUv > 0) {
                (maxCurrentUa.toLong() * maxVoltageUv.toLong()) / 1_000_000_000_000.0
            } else 0.0
            return if (watts > 10) "Qi (Fast)" else "Qi"
        }
        if (maxCurrentUa <= 0 || maxVoltageUv <= 0) return "Unknown"

        val voltsV = maxVoltageUv / 1_000_000.0
        val ampsA = maxCurrentUa / 1_000_000.0
        val watts = voltsV * ampsA

        // UFCS: supports variable voltage, high current (>3A at >5V)
        // PPS: programmable power supply, variable voltage
        // PD: fixed voltage steps
        return when {
            watts > 30 && ampsA > 3.0 -> "UFCS"      // High power + high current = UFCS
            watts > 18 && voltsV > 5.5 -> "PD/PPS"    // >18W with elevated voltage
            voltsV > 8.5 && voltsV <= 10 -> "PD 9V"   // 9V PD
            voltsV > 14 && voltsV <= 16 -> "PD 15V"    // 15V PD
            voltsV > 19 && voltsV <= 21 -> "PD 20V"    // 20V PD
            voltsV > 5.5 -> "PD/QC"                     // Elevated voltage
            ampsA > 2.0 -> "PD 5V"                      // 5V high current
            ampsA > 0.5 -> "USB"                         // Standard USB
            else -> "Trickle"
        }
    }

    private fun getSystemProp(key: String): String {
        val cached = propCache[key]
        if (cached != null && System.currentTimeMillis() - cached.second < PROP_CACHE_TTL_MS) {
            return cached.first
        }
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("getprop", key))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val value = reader.readLine()?.trim() ?: ""
            reader.close()
            process.waitFor()
            propCache[key] = value to System.currentTimeMillis()
            value
        } catch (e: Exception) { "" }
    }

    /**
     * Returns the current battery snapshot AND reconciles session state as a side
     * effect. The name is "get" but the call also:
     *   1. Ends any active discharge session if currently charging (and vice versa) —
     *      a tertiary safety net behind ChargingReceiver and BatteryWorker.
     *   2. Auto-starts a session matching the current state if none is active.
     *   3. Records one data point into the active session, throttled to
     *      [RECORD_THROTTLE_MS] (2 min) since the last recorded point.
     *
     * Callers in JS (Battery.jsx mount, BatterySync.jsx triggers) rely on this
     * mutating behavior to keep the DB in sync regardless of which entry path
     * (broadcast / worker / page open) is hit. Do not call this in tight loops.
     */
    @PluginMethod
    fun getCurrentStats(call: PluginCall) {
        val bm = context.getSystemService(android.content.Context.BATTERY_SERVICE) as BatteryManager
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val currentUa = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        val chargeUah = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)

        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val voltMv = batteryIntent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
        val tempTenths = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val plugged = batteryIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val health = batteryIntent?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1) ?: -1
        val technology = batteryIntent?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY) ?: ""

        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL
        val chargeType = when (plugged) {
            BatteryManager.BATTERY_PLUGGED_AC -> "Wired"
            BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
            else -> "None"
        }
        val healthStr = when (health) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheat"
            BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over Voltage"
            BatteryManager.BATTERY_HEALTH_COLD -> "Cold"
            else -> "Unknown"
        }

        var activeChargeSessionId = ChargingReceiver.getActiveChargeSessionId(context)
        var activeDischargeSessionId = DischargeWorker.getActiveDischargeSessionId(context)
        val hasUsagePermission = AppUsageTracker(context).hasPermission()

        // Clean up stale sessions: end discharge session if now charging, end charge if discharging
        if (isCharging && activeDischargeSessionId > 0) {
            DischargeWorker.endDischargeSession(context)
            activeDischargeSessionId = -1
        }
        if (!isCharging && activeChargeSessionId > 0) {
            ChargingReceiver.endChargeSession(context)
            activeChargeSessionId = -1
        }

        // Auto-start: if discharging but no session, start one
        if (!isCharging && activeDischargeSessionId <= 0) {
            DischargeWorker.startDischargeSession(context)
            activeDischargeSessionId = DischargeWorker.getActiveDischargeSessionId(context)
        }
        // Auto-start: if charging but no session, start one
        if (isCharging && activeChargeSessionId <= 0) {
            ChargingReceiver.startChargeSession(context)
            activeChargeSessionId = ChargingReceiver.getActiveChargeSessionId(context)
        }

        // Record a live data point — throttled to once per 2 minutes to save battery.
        // Throttle state is persisted in SharedPreferences so it survives process death
        // (otherwise the first call after every app restart would record an extra point).
        val now = System.currentTimeMillis()
        val prefs = getPrefs()
        val lastRecorded = prefs.getLong(KEY_LAST_RECORDED_POINT, 0L)
        if (now - lastRecorded >= RECORD_THROTTLE_MS) {
            if (isCharging && activeChargeSessionId > 0) {
                ChargingReceiver.recordPoint(context, dbHelper, activeChargeSessionId)
            } else if (!isCharging && activeDischargeSessionId > 0) {
                DischargeWorker.recordPoint(context, dbHelper, activeDischargeSessionId)
            }
            prefs.edit().putLong(KEY_LAST_RECORDED_POINT, now).apply()
        }

        // Battery health metrics
        val cycleCount = readCycleCount(bm)
        val vendorHealth = getSystemProp("vendor.nt.battery_info.health").toIntOrNull() ?: -1
        val currentMa = currentUa / 1000
        val chargeMah = chargeUah / 1000

        // Design capacity: dynamic read, 0 = unknown
        val designCapacityMahRaw = readDesignCapacityMah()

        // Calculate actual capacity from charge counter and level
        //   actualCapacity = chargeCounter / (level/100)
        // Only valid in the linear discharge region (~10–90%). Outside that:
        //   - <10%: fuel gauge re-calibrates near the knee, ratio is noisy.
        //   - >90%: charging enters constant-voltage phase, charge_counter
        //           lags actual SoC. Ratio under-estimates capacity.
        // To avoid these regions, fall back to last known good estimate stored
        // in SharedPreferences, or designCapacityMah on first run.
        val actualCapacityMah = if (pct in 10..90 && chargeMah > 100) {
            val estimate = Math.round(chargeMah.toDouble() / (pct.toDouble() / 100.0)).toInt()
            // Only write when the estimate moves by >=20 mAh — avoids
            // touching SharedPreferences on every 8-second JS poll.
            val prefs = getPrefs()
            val cached = prefs.getInt("last_actual_capacity_mah", 0)
            if (kotlin.math.abs(estimate - cached) >= 20) {
                prefs.edit().putInt("last_actual_capacity_mah", estimate).apply()
            }
            estimate
        } else {
            val cached = getPrefs().getInt("last_actual_capacity_mah", 0)
            if (cached > 0) cached else designCapacityMahRaw
        }

        val designCapacityMah = if (designCapacityMahRaw == 0 && actualCapacityMah > 0) {
            val commonSizes = listOf(3000, 3300, 3500, 4000, 4300, 4500, 4800, 5000, 5160, 5500, 6000)
            commonSizes.find { it >= actualCapacityMah } ?: (((actualCapacityMah + 499) / 500) * 500)
        } else {
            designCapacityMahRaw
        }

        val healthPct = if (actualCapacityMah > 0 && designCapacityMah > 0) {
            Math.min(100.0, (actualCapacityMah.toDouble() / designCapacityMah.toDouble()) * 100.0)
        } else -1.0 // -1 = unknown, UI will hide health card

        // Once-per-day capacity snapshot for the degradation timeline graph.
        // Only take the snapshot when we're in the linear 10–90% region so the
        // estimate is trustworthy (outside this range the fuel gauge is noisy).
        if (pct in 10..90 && actualCapacityMah > 0 && !dbHelper.hasCapacitySnapshotToday()) {
            try {
                dbHelper.insertCapacitySnapshot(
                    actualCapacityMah = actualCapacityMah,
                    designCapacityMah = designCapacityMah,
                    cycleCount = cycleCount,
                    healthPct = healthPct,
                    ntSoH = -1,  // deferred — readNtSoH() runs below and caches separately
                    pctWhenTaken = pct,
                )
            } catch (_: Exception) { }
        }

        // Nothing charge limit: nt_battery_health setting
        // 0=off, 1=smart charging, 2=custom 70%, 3=custom 80%, 4=custom 90%
        val ntBatteryHealth = try {
            Settings.System.getInt(context.contentResolver, "nt_battery_health", 0)
        } catch (_: Exception) { 0 }
        val chargeLimit = when (ntBatteryHealth) {
            2 -> 70
            3 -> 80
            4 -> 90
            else -> 100 // off or smart charging
        }

        // Max charging power (μA and μV from battery intent)
        val maxChargingCurrentUa = batteryIntent?.getIntExtra("max_charging_current", 0) ?: 0
        val maxChargingVoltageUv = batteryIntent?.getIntExtra("max_charging_voltage", 0) ?: 0
        val chargingWatts = if (maxChargingCurrentUa > 0 && maxChargingVoltageUv > 0) {
            (maxChargingCurrentUa.toLong() * maxChargingVoltageUv.toLong()) / 1_000_000_000_000.0
        } else 0.0

        // Smoothed drain rate: average last N current readings to avoid wild fluctuations
        currentMaHistory.add(currentMa)
        if (currentMaHistory.size > SMOOTHING_WINDOW) currentMaHistory.removeAt(0)
        val smoothedMa = if (currentMaHistory.isNotEmpty()) {
            currentMaHistory.map { Math.abs(it) }.average()
        } else Math.abs(currentMa.toDouble())
        val drainPctPerHr = if (smoothedMa > 0 && actualCapacityMah > 0) {
            smoothedMa / actualCapacityMah.toDouble() * 100.0
        } else 0.0

        // ETA: use chargeLimit instead of 100% when charging
        val etaHrs = if (drainPctPerHr > 0) {
            if (isCharging) {
                val targetPct = chargeLimit.toDouble()
                if (pct < targetPct) (targetPct - pct) / drainPctPerHr else 0.0
            } else pct.toDouble() / drainPctPerHr
        } else 0.0

        // NtCharge SoH: read from logcat once per 15 days, cached between reads
        val ntSoH = readNtSoH()

        // Charge protocol: inferred from negotiated voltage/current
        val chargeProtocol = if (isCharging) {
            inferChargeProtocol(plugged, maxChargingCurrentUa, maxChargingVoltageUv)
        } else ""

        val ret = JSObject().apply {
            put("level", pct)
            put("isCharging", isCharging)
            put("chargeType", chargeType)
            put("voltMv", voltMv)
            put("currentMa", currentMa)
            put("chargeCounterUah", chargeUah)
            put("temperature", tempTenths / 10.0)
            put("health", healthStr)
            put("technology", technology)
            put("activeChargeSessionId", activeChargeSessionId)
            put("activeDischargeSessionId", activeDischargeSessionId)
            put("hasUsagePermission", hasUsagePermission)
            put("deviceModel", Build.MODEL)
            put("deviceManufacturer", Build.MANUFACTURER)
            // Friendly device name (e.g. "Nothing Phone (3)")
            val friendlyName = getSystemProp("ro.product.brand_device_name")
            put("deviceName", if (friendlyName.isNotEmpty()) friendlyName else "${Build.MANUFACTURER} ${Build.MODEL}")
            // Battery health
            put("cycleCount", cycleCount)
            put("vendorHealthPct", vendorHealth)
            put("designCapacityMah", designCapacityMah)
            put("actualCapacityMah", actualCapacityMah)
            put("healthPct", Math.round(healthPct * 10.0) / 10.0)
            put("chargeMah", chargeMah)
            // Charge control
            put("chargeLimit", chargeLimit)
            put("chargingWatts", Math.round(chargingWatts * 10.0) / 10.0)
            // Negotiated voltage/current (what charger and phone agreed on)
            put("negotiatedVoltageV", Math.round(maxChargingVoltageUv / 1_000_000.0 * 100) / 100.0)
            put("negotiatedCurrentA", Math.round(maxChargingCurrentUa / 1_000_000.0 * 100) / 100.0)
            // NtCharge SoH & protocol
            put("ntSoH", ntSoH)
            put("chargeProtocol", chargeProtocol)
            // Computed rates
            put("drainPctPerHr", Math.round(drainPctPerHr * 10.0) / 10.0)
            put("etaHrs", Math.round(etaHrs * 100.0) / 100.0)
            // Unique device identifier for multi-device support
            val deviceId = android.provider.Settings.Secure.getString(
                context.contentResolver, android.provider.Settings.Secure.ANDROID_ID
            ) ?: "unknown"
            put("deviceId", deviceId)
        }
        call.resolve(ret)
    }

    @PluginMethod
    fun getSessionData(call: PluginCall) {
        // Capacitor sends JS numbers as Integer, not Long — getLong returns null
        val sessionId = (call.getInt("sessionId")?.toLong()
            ?: call.getDouble("sessionId")?.toLong()) ?: run {
            call.reject("sessionId is required")
            return
        }
        val points = dbHelper.getPointsForSession(sessionId)
        val arr = JSArray()
        for (p in points) {
            arr.put(JSObject().apply {
                put("time", p["time"])
                put("pct", p["pct"])
                put("voltMv", p["voltMv"])
                put("currentMa", p["currentMa"])
                put("chargeCounterUah", p["chargeCounterUah"])
                put("tempC", p["tempC"])
            })
        }
        val capacity = dbHelper.getCapacityForSession(sessionId)
        val sessionType = dbHelper.getSessionType(sessionId)

        val sessions = dbHelper.getAllSessions()
        val sessionInfo = sessions.find { (it["id"] as Long) == sessionId }

        // App usage for discharge sessions — prefer live data if session is active
        val appUsageArr = JSArray()
        if (sessionType == "discharge") {
            val startTime = sessionInfo?.get("startTime") as? Long ?: 0L
            val endTime = sessionInfo?.get("endTime") as? Long ?: 0L
            val isActive = endTime == 0L

            if (isActive && startTime > 0) {
                // Active session: compute foreground + foreground-service time
                // precisely over [startTime, now] via queryEvents. The fgs
                // bucket is critical for accurate per-app drain attribution;
                // foreground-service-only drainers (sync, music, navigation,
                // alarm/always-on apps) would otherwise be invisible.
                val tracker = AppUsageTracker(context)
                if (tracker.hasPermission()) {
                    val now = System.currentTimeMillis()
                    val pkgMap = tracker.getPackageTimeRange(startTime, now)
                    // Enrich with per-UID network bytes from NetworkStatsManager.
                    // For apps that drain primarily via push / sync / scheduled
                    // jobs (rather than foreground UI or services) this is
                    // the strongest drain proxy a non-privileged app can see.
                    val netHelper = NetworkStatsHelper(context)
                    val bytesPerUid = netHelper.getBytesPerUid(startTime, now)
                    val pm = context.packageManager
                    // Union the two package sets: apps with fg/fgs time AND
                    // apps with significant network activity even if they
                    // never came to the foreground in this window.
                    val uidToBytes = bytesPerUid  // capture for closure
                    fun uidFor(pkg: String): Int? = try {
                        pm.getPackageInfo(pkg, 0).applicationInfo?.uid
                    } catch (e: Exception) { null }
                    val pkgToUid = HashMap<String, Int>()
                    for (p in pkgMap.keys) uidFor(p)?.let { pkgToUid[p] = it }
                    // Also include packages with network bytes but no fg/fgs.
                    val extraPkgs = mutableListOf<String>()
                    for ((uid, bytes) in uidToBytes) {
                        // Only surface packages that had meaningful network
                        // activity (≥ 512 KiB total) so we don't clutter the
                        // list with background heartbeats. This threshold
                        // matches the rough floor below which network drain
                        // is negligible on a modern radio.
                        if (bytes.totalBytes < 512L * 1024L) continue
                        val pkgs = pm.getPackagesForUid(uid) ?: continue
                        // A UID can be shared across packages (rare, system
                        // apps). Attribute to the first package; PackageManager
                        // orders them deterministically.
                        val pkg = pkgs.firstOrNull() ?: continue
                        if (!pkgMap.containsKey(pkg)) {
                            extraPkgs.add(pkg)
                            pkgToUid[pkg] = uid
                        }
                    }
                    // Build unified ranked list. Primary sort key is combined
                    // active time (fg + fgs). Packages with zero active time
                    // but non-trivial network bytes fall below all active
                    // apps but still appear.
                    val allPkgs = (pkgMap.keys + extraPkgs).distinct()
                    allPkgs.asSequence()
                        .map { pkg ->
                            val t = pkgMap[pkg]
                            val b = pkgToUid[pkg]?.let { uidToBytes[it] }
                            Triple(pkg, t, b)
                        }
                        .filter { (_, t, b) ->
                            (t?.activeMs ?: 0L) > 0L || (b?.totalBytes ?: 0L) >= 512L * 1024L
                        }
                        // Composite score: active time (ms) + network bytes
                        // scaled to a comparable magnitude. 1 MiB ≈ 1 s of
                        // foreground — a coarse but empirically reasonable
                        // equivalence for ranking purposes.
                        .sortedByDescending { (_, t, b) ->
                            val activeMs = t?.activeMs ?: 0L
                            val bytes = b?.totalBytes ?: 0L
                            activeMs + (bytes / 1024L)   // 1 KiB ≈ 1 ms
                        }
                        .take(30)
                        .forEach { (pkg, t, b) ->
                            appUsageArr.put(JSObject().apply {
                                put("packageName", pkg)
                                put("appName", tracker.resolveAppLabel(pkg))
                                put("foregroundMs", t?.foregroundMs ?: 0L)
                                put("foregroundServiceMs", t?.foregroundServiceMs ?: 0L)
                                put("backgroundMs", 0L)
                                put("rxBytes", b?.rxBytes ?: 0L)
                                put("txBytes", b?.txBytes ?: 0L)
                                put("mobileBytes", b?.mobileBytes ?: 0L)
                            })
                        }
                }
            } else {
                // Stored snapshots for completed sessions. Old DB rows may
                // contain a raw fallback name written before the manifest
                // visibility fix; re-resolve via the centralized helper so
                // historical sessions render correctly without any DB
                // migration.
                val tracker = AppUsageTracker(context)
                val appUsage = dbHelper.getAppUsageForSession(sessionId)
                for (u in appUsage) {
                    val pkg = u["packageName"] as? String
                    val storedName = u["appName"] as? String
                    val resolvedName = if (pkg != null) tracker.resolveAppLabel(pkg) else storedName
                    appUsageArr.put(JSObject().apply {
                        put("packageName", pkg)
                        put("appName", resolvedName ?: storedName ?: pkg)
                        put("foregroundMs", u["foregroundMs"])
                        // Older rows may not have this column (DB v3); coerce
                        // a missing value to 0 instead of letting it surface
                        // as null in JS where the React layer would NaN out.
                        put("foregroundServiceMs", (u["foregroundServiceMs"] as? Long) ?: 0L)
                        put("backgroundMs", u["backgroundMs"])
                    })
                }
            }
        }

        val ret = JSObject().apply {
            put("sessionId", sessionId)
            put("startTime", sessionInfo?.get("startTime") ?: 0)
            put("endTime", sessionInfo?.get("endTime") ?: 0)
            put("type", sessionType)
            put("points", arr)
            put("appUsage", appUsageArr)
            put("estimatedCapacityMah", capacity)
        }
        call.resolve(ret)
    }

    @PluginMethod
    fun getAllSessions(call: PluginCall) {
        val typeFilter = call.getString("type")
        val sessions = dbHelper.getAllSessionsWithStats(typeFilter)
        val arr = JSArray()
        for (s in sessions) {
            arr.put(JSObject().apply {
                put("id", s["id"])
                put("startTime", s["startTime"])
                put("endTime", s["endTime"])
                put("type", s["type"])
                put("pointCount", s["pointCount"])
                put("startPct", s["startPct"])
                put("endPct", s["endPct"])
            })
        }
        val ret = JSObject().apply { put("sessions", arr) }
        call.resolve(ret)
    }

    @PluginMethod
    fun startMonitoring(call: PluginCall) {
        ChargingReceiver.startChargeSession(context)
        call.resolve(JSObject().apply { put("started", true) })
    }

    @PluginMethod
    fun stopMonitoring(call: PluginCall) {
        ChargingReceiver.endChargeSession(context)
        call.resolve(JSObject().apply { put("stopped", true) })
    }

    @PluginMethod
    fun startDischargeMonitoring(call: PluginCall) {
        DischargeWorker.startDischargeSession(context)
        call.resolve(JSObject().apply { put("started", true) })
    }

    @PluginMethod
    fun stopDischargeMonitoring(call: PluginCall) {
        DischargeWorker.endDischargeSession(context)
        call.resolve(JSObject().apply { put("stopped", true) })
    }

    @PluginMethod
    fun requestUsagePermission(call: PluginCall) {
        val intent = Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        call.resolve(JSObject().apply { put("opened", true) })
    }

    @PluginMethod
    fun getAppUsage(call: PluginCall) {
        val tracker = AppUsageTracker(context)
        if (!tracker.hasPermission()) {
            call.resolve(JSObject().apply {
                put("hasPermission", false)
                put("apps", JSArray())
            })
            return
        }
        val sinceMs = call.getLong("since") ?: (System.currentTimeMillis() - 24 * 3600_000L)
        val nowMs = System.currentTimeMillis()
        val apps = tracker.getUsageSince(sinceMs)
        // Enrich with per-UID network bytes. This lets apps that drain via
        // push / sync / scheduled jobs (rather than foreground UI or
        // services) surface alongside heavy-UI apps. See NetworkStatsHelper
        // KDoc for rationale and permission model.
        val netHelper = NetworkStatsHelper(context)
        val bytesPerUid = netHelper.getBytesPerUid(sinceMs, nowMs)
        val pm = context.packageManager
        fun uidFor(pkg: String): Int? = try {
            pm.getPackageInfo(pkg, 0).applicationInfo?.uid
        } catch (e: Exception) { null }
        val pkgToUid = HashMap<String, Int>()
        for (a in apps) uidFor(a.packageName)?.let { pkgToUid[a.packageName] = it }
        // Also include packages with significant network bytes but no fg/fgs.
        val existing = apps.map { it.packageName }.toMutableSet()
        val extras = mutableListOf<AppUsageTracker.AppUsageInfo>()
        for ((uid, bytes) in bytesPerUid) {
            if (bytes.totalBytes < 512L * 1024L) continue
            val pkgs = pm.getPackagesForUid(uid) ?: continue
            val pkg = pkgs.firstOrNull() ?: continue
            if (pkg in existing) continue
            extras.add(AppUsageTracker.AppUsageInfo(
                packageName = pkg,
                appName = tracker.resolveAppLabel(pkg),
                foregroundMs = 0L,
                foregroundServiceMs = 0L,
                backgroundMs = 0L,
            ))
            pkgToUid[pkg] = uid
            existing.add(pkg)
        }
        val combined = (apps + extras).sortedByDescending { a ->
            val activeMs = a.foregroundMs + a.foregroundServiceMs
            val bytes = pkgToUid[a.packageName]?.let { bytesPerUid[it]?.totalBytes } ?: 0L
            activeMs + (bytes / 1024L)  // 1 KiB ≈ 1 ms drain equivalence
        }.take(30)
        val arr = JSArray()
        for (a in combined) {
            val b = pkgToUid[a.packageName]?.let { bytesPerUid[it] }
            arr.put(JSObject().apply {
                put("packageName", a.packageName)
                put("appName", a.appName)
                put("foregroundMs", a.foregroundMs)
                put("foregroundServiceMs", a.foregroundServiceMs)
                put("backgroundMs", a.backgroundMs)
                put("rxBytes", b?.rxBytes ?: 0L)
                put("txBytes", b?.txBytes ?: 0L)
                put("mobileBytes", b?.mobileBytes ?: 0L)
            })
        }
        call.resolve(JSObject().apply {
            put("hasPermission", true)
            put("apps", arr)
        })
    }

    @PluginMethod
    fun deleteSession(call: PluginCall) {
        val sessionId = (call.getInt("sessionId")?.toLong()
            ?: call.getDouble("sessionId")?.toLong()) ?: run {
            call.reject("sessionId is required")
            return
        }
        dbHelper.deleteSession(sessionId)
        call.resolve(JSObject().apply { put("deleted", true) })
    }

    /**
     * Returns the daily capacity snapshots for the degradation timeline graph.
     * Default: last 365 days.
     */
    @PluginMethod
    fun getCapacityHistory(call: PluginCall) {
        val sinceMs = call.getLong("since") ?: 0L
        val limit = call.getInt("limit") ?: 365
        val snapshots = dbHelper.getCapacitySnapshots(sinceMs, limit)
        val arr = JSArray()
        for (s in snapshots) {
            arr.put(JSObject().apply {
                put("time", s["time"])
                put("actualCapacityMah", s["actualCapacityMah"])
                put("designCapacityMah", s["designCapacityMah"])
                put("cycleCount", s["cycleCount"])
                put("healthPct", s["healthPct"])
                put("ntSoH", s["ntSoH"])
                put("pctWhenTaken", s["pctWhenTaken"])
            })
        }
        call.resolve(JSObject().apply { put("snapshots", arr) })
    }

    /**
     * On-demand retention sweep. Called by the JS side on Battery-page mount
     * so the user sees the effect immediately on first app open rather than
     * waiting for the next 30-min worker tick. Default retentionDays = 10.
     * Never deletes active sessions.
     */
    @PluginMethod
    fun purgeOldData(call: PluginCall) {
        val retentionDays = call.getInt("retentionDays") ?: 10
        val cutoff = System.currentTimeMillis() - retentionDays * 86_400_000L
        val deletedOld = dbHelper.deleteOldData(cutoff)
        val healed = dbHelper.healTailPollution()
        val deletedMicro = dbHelper.purgeMicroSessions()
        call.resolve(JSObject().apply {
            put("deletedOldSessions", deletedOld)
            put("healedSessions", healed)
            put("deletedMicroSessions", deletedMicro)
            put("retentionDays", retentionDays)
        })
    }
}
