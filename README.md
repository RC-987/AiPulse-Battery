# Battery Monitor APK

A standalone Android battery monitoring app extracted from AiPulse. Tracks battery stats, charge/discharge sessions, app-level drain, and battery health in real-time.

## Features

- **Live battery stats** — level, voltage, current, temperature, charge type
- **Charge/discharge session tracking** — automatic with background workers
- **Interactive charts** — touch-enabled charge/discharge curves
- **App usage drain** — per-app battery drain estimation using Android UsageStats
- **Battery health** — capacity degradation, cycle count, SoH tracking
- **Boot persistence** — survives device reboots via BootReceiver

## Tech Stack

- **Frontend**: React + Vite + Tailwind CSS (Capacitor WebView)
- **Native**: Kotlin (Android) — 8 native modules
- **Bridge**: Capacitor 8

## Project Structure

```
src/pages/Battery.jsx        # Main battery UI (charts, stats, sessions)
src/utils/batteryPlugin.js   # Capacitor plugin bridge
android/.../battery/
  BatteryMonitorPlugin.kt    # Capacitor plugin entry point
  BatteryDbHelper.kt         # SQLite database for sessions
  AppUsageTracker.kt          # Android UsageStats wrapper
  BatteryWorker.kt            # WorkManager periodic worker
  DischargeWorker.kt          # Discharge session manager
  BootReceiver.kt             # Boot-completed receiver
  ChargingReceiver.kt         # Power connect/disconnect receiver
  NetworkStatsHelper.kt       # Per-UID network stats
```

## Install

Download `BatteryMonitor-debug.apk` from this repo and install on your Android device.

## Build from Source

`ash
npm install
npm run build
npx cap sync android
cd android && ./gradlew assembleDebug
`

APK output: `android/app/build/outputs/apk/debug/app-debug.apk`

**Requires**: Node.js, JDK 21+, Android SDK (API 36)
