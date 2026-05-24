import { useEffect, useRef, useState, useCallback } from 'react'
import { App as CapApp } from '@capacitor/app'
import { isNativePlatform } from '../firebase'
import { useApp } from '../App'
import { BatteryMonitor } from '../utils/batteryPlugin'
import { istMidnightMs, nextIstMidnightMs } from '../utils/time'
const TABS = ['Overview', 'Discharge', 'App Usage']

const LIVE_POLL_MS = 8000

function fmtTime(ts) {
  const d = new Date(ts)
  let h = d.getHours(), ampm = h >= 12 ? 'PM' : 'AM'
  h = h % 12 || 12
  return `${h}:${String(d.getMinutes()).padStart(2,'0')} ${ampm}`
}
function fmtDate(ts) {
  if (!ts) return '—'
  const d = new Date(ts)
  let h = d.getHours(), ampm = h >= 12 ? 'PM' : 'AM'
  h = h % 12 || 12
  return `${d.getDate()} ${d.toLocaleString('en',{month:'short'})} ${h}:${String(d.getMinutes()).padStart(2,'0')} ${ampm}`
}
function fmtDuration(ms) {
  if (!ms || ms <= 0) return '—'
  const m = Math.floor(ms / 60000)
  const h = Math.floor(m / 60)
  return h > 0 ? `${h}h ${m % 60}m` : `${m}m`
}
function fmtMs(ms) {
  if (!ms || ms <= 0) return '0s'
  const s = Math.floor(ms / 1000)
  if (s < 60) return `${s}s`
  const m = Math.floor(s / 60)
  const h = Math.floor(m / 60)
  return h > 0 ? `${h}h ${m % 60}m` : `${m}m`
}
function fmtBytes(b) {
  if (!b || b <= 0) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB']
  let i = 0
  let v = b
  while (v >= 1024 && i < units.length - 1) { v /= 1024; i++ }
  return `${v < 10 ? v.toFixed(1) : Math.round(v)} ${units[i]}`
}

export default function Battery() {
  const { showToast } = useApp()
  const [tab, setTab] = useState(0)
  const [stats, setStats] = useState(null)
  const [sessionData, setSessionData] = useState(null)
  const [sessions, setSessions] = useState([])
  const [selectedSession, setSelectedSession] = useState(null)
  const [appUsage, setAppUsage] = useState(null)
  const [sessionFilter, setSessionFilter] = useState('all')
  const [hoverIdx, setHoverIdx] = useState(-1)
  const [capacityHistory, setCapacityHistory] = useState([])
  const [_retentionInfo, setRetentionInfo] = useState(null)
  const canvasRef = useRef(null)
  const capacityCanvasRef = useRef(null)
  const deviceIdRef = useRef(null)
  const userSelectedRef = useRef(false)
  const selectedSessionRef = useRef(null)
  const pollIntervalRef = useRef(null)

  // ── Native polling (local only) ─────────────────
  const pollNative = useCallback(async () => {
    if (!isNativePlatform) return
    try {
      const s = await BatteryMonitor.getCurrentStats()
      setStats(s)
      if (s.deviceId && !deviceIdRef.current) {
        deviceIdRef.current = s.deviceId
      }

      const activeId = s.isCharging ? s.activeChargeSessionId : s.activeDischargeSessionId
      if (activeId > 0) {
        const shown = selectedSessionRef.current
        const followLive = !userSelectedRef.current || shown == null || shown === activeId
        if (followLive) {
          try {
            const sd = await BatteryMonitor.getSessionData({ sessionId: activeId })
            setSessionData(sd)
            if (!userSelectedRef.current) {
              setSelectedSession(activeId)
              selectedSessionRef.current = activeId
            }
          } catch (e) { console.warn('getSessionData error:', e) }
        }
      }

      try {
        const sl = await BatteryMonitor.getAllSessions({})
        setSessions(sl.sessions || [])
      } catch (e) { console.warn('getAllSessions error:', e) }
    } catch (e) {
      console.warn('BatteryMonitor poll error:', e)
    }
  }, [])

  // ── Native: poll local data on mount + live refresh ────────────
  useEffect(() => {
    if (!isNativePlatform) return
    pollNative()
    BatteryMonitor.purgeOldData({ retentionDays: 10 })
      .then(setRetentionInfo)
      .catch(() => {})
    BatteryMonitor.getCapacityHistory({})
      .then(r => setCapacityHistory(r?.snapshots || []))
      .catch(() => {})
    const startInterval = () => {
      if (pollIntervalRef.current) return
      pollIntervalRef.current = setInterval(() => {
        if (!document.hidden) pollNative()
      }, LIVE_POLL_MS)
    }
    const stopInterval = () => {
      if (pollIntervalRef.current) {
        clearInterval(pollIntervalRef.current)
        pollIntervalRef.current = null
      }
    }
    startInterval()

    const onVisibility = () => {
      if (document.hidden) {
        stopInterval()
      } else {
        pollNative()
        startInterval()
      }
    }
    document.addEventListener('visibilitychange', onVisibility)

    let appStateListener
    CapApp.addListener('appStateChange', ({ isActive }) => {
      if (isActive) pollNative()
    }).then(l => { appStateListener = l })

    return () => {
      stopInterval()
      document.removeEventListener('visibilitychange', onVisibility)
      appStateListener?.remove()
    }
  }, [pollNative])

  // ── Load app usage (only when on tab 2) ─────────
  useEffect(() => {
    if (!isNativePlatform || tab !== 2) return
    let cancelled = false
    let midnightTimer = null
    const load = async () => {
      try {
        const res = await BatteryMonitor.getAppUsage({ since: istMidnightMs() })
        if (!cancelled) setAppUsage(res)
      } catch (e) { console.warn('App usage error:', e) }
    }
    const scheduleMidnightReload = () => {
      const delay = nextIstMidnightMs() - Date.now() + 1000
      midnightTimer = setTimeout(() => {
        if (cancelled) return
        load().finally(scheduleMidnightReload)
      }, delay)
    }
    load()
    scheduleMidnightReload()
    return () => {
      cancelled = true
      if (midnightTimer) clearTimeout(midnightTimer)
    }
  }, [tab])

  // ── Load session ─────────────────────────────────────
  const loadSession = async (sid) => {
    setSelectedSession(sid)
    selectedSessionRef.current = sid
    userSelectedRef.current = true
    if (isNativePlatform) {
      try {
        const sd = await BatteryMonitor.getSessionData({ sessionId: sid })
        setSessionData(sd)
      } catch (e) { console.warn('Load session error:', e) }
    }
  }

  // ── Derived stats ───────────────────────────────────
  const hasPhoneData = isNativePlatform && !!stats
  const level = stats?.level ?? 0
  const isCharging = stats?.isCharging
  const drainPctPerHr = stats?.drainPctPerHr ?? 0
  const etaHrs = stats?.etaHrs ?? 0

  const chargeSessions = sessions.filter(s => s.type === 'charge' || !s.type)
  const dischargeSessions = sessions.filter(s => s.type === 'discharge')

  const sessionAppUsage = sessionData?.appUsage || []
  const sessionSignedDelta = (sessionData?.points?.length >= 2)
    ? sessionData.points[sessionData.points.length - 1].pct - sessionData.points[0].pct
    : 0
  const sessionDrain = Math.abs(sessionSignedDelta)

  const sessionSignedDeltaPct = (s) => {
    if (s == null || s.startPct == null || s.endPct == null) return null
    return s.endPct - s.startPct
  }

  const sessionStart = sessionData?.startTime || 0
  const sessionEnd = sessionData?.endTime || 0
  const sessionDur = sessionStart > 0
    ? (sessionEnd > 0 ? sessionEnd - sessionStart : Date.now() - sessionStart)
    : 0

  // ── Chart ───────────────────────────────────────────
  const points = (hasPhoneData && sessionData?.points?.length > 0) ? sessionData.points : []
  const sessionType = sessionData?.type || 'charge'
  const isDischargeView = sessionType === 'discharge'

  useEffect(() => {
    if (!canvasRef.current || points.length < 2) return
    const canvas = canvasRef.current
    const ctx = canvas.getContext('2d')
    const dpr = window.devicePixelRatio || 1
    const w = canvas.clientWidth
    const h = canvas.clientHeight
    canvas.width = w * dpr
    canvas.height = h * dpr
    ctx.scale(dpr, dpr)
    ctx.clearRect(0, 0, w, h)

    const pad = { top: 20, right: 15, bottom: 30, left: 35 }
    const cw = w - pad.left - pad.right
    const ch = h - pad.top - pad.bottom
    const minT = points[0].time
    const maxT = points[points.length - 1].time
    const rangeT = maxT - minT || 1
    const xOf = (t) => pad.left + ((t - minT) / rangeT) * cw
    const yOf = (p) => pad.top + (1 - p / 100) * ch
    const color = isDischargeView ? '#E5A100' : '#1D9E75'

    ctx.strokeStyle = 'rgba(128,128,128,0.15)'
    ctx.lineWidth = 1
    for (const g of [0, 25, 50, 75, 100]) {
      const y = yOf(g)
      ctx.beginPath(); ctx.moveTo(pad.left, y); ctx.lineTo(w - pad.right, y); ctx.stroke()
      ctx.fillStyle = '#666'; ctx.font = '9px monospace'; ctx.textAlign = 'right'
      ctx.fillText(`${g}%`, pad.left - 4, y + 3)
    }

    ctx.fillStyle = '#555'; ctx.font = '9px monospace'; ctx.textAlign = 'center'
    const minute = 60_000, hour = 60 * minute
    const intervals = [
      minute, 2*minute, 5*minute, 10*minute, 15*minute, 30*minute,
      hour, 2*hour, 3*hour, 6*hour, 12*hour, 24*hour,
    ]
    const targetTicks = 6
    const tickInterval = intervals.find(iv => rangeT / iv <= targetTicks) || 24*hour
    const firstTick = Math.ceil(minT / tickInterval) * tickInterval
    let lastLabelX = -Infinity
    let labelsDrawn = 0
    for (let t = firstTick; t <= maxT; t += tickInterval) {
      const x = xOf(t)
      if (x - lastLabelX < 45) continue
      ctx.fillText(fmtTime(t), x, h - 8)
      ctx.strokeStyle = 'rgba(128,128,128,0.25)'
      ctx.beginPath(); ctx.moveTo(x, h - pad.bottom); ctx.lineTo(x, h - pad.bottom + 3); ctx.stroke()
      lastLabelX = x
      labelsDrawn++
    }
    if (labelsDrawn === 0) {
      ctx.fillText(fmtTime(minT), xOf(minT) + 8, h - 8)
      ctx.textAlign = 'right'
      ctx.fillText(fmtTime(maxT), xOf(maxT) - 2, h - 8)
      ctx.textAlign = 'center'
    }

    const grad = ctx.createLinearGradient(0, pad.top, 0, h - pad.bottom)
    grad.addColorStop(0, isDischargeView ? 'rgba(229,161,0,0.2)' : 'rgba(29,158,117,0.25)')
    grad.addColorStop(1, isDischargeView ? 'rgba(229,161,0,0)' : 'rgba(29,158,117,0)')
    ctx.beginPath()
    ctx.moveTo(xOf(points[0].time), yOf(points[0].pct))
    points.slice(1).forEach(p => ctx.lineTo(xOf(p.time), yOf(p.pct)))
    ctx.lineTo(xOf(points[points.length - 1].time), h - pad.bottom)
    ctx.lineTo(xOf(points[0].time), h - pad.bottom)
    ctx.closePath()
    ctx.fillStyle = grad; ctx.fill()

    ctx.beginPath()
    ctx.moveTo(xOf(points[0].time), yOf(points[0].pct))
    points.slice(1).forEach(p => ctx.lineTo(xOf(p.time), yOf(p.pct)))
    ctx.strokeStyle = color; ctx.lineWidth = 2; ctx.lineJoin = 'round'; ctx.lineCap = 'round'; ctx.stroke()

    points.forEach(p => {
      ctx.beginPath(); ctx.arc(xOf(p.time), yOf(p.pct), 3, 0, Math.PI * 2)
      ctx.fillStyle = color; ctx.fill()
    })

    if (hoverIdx >= 0 && hoverIdx < points.length) {
      const hp = points[hoverIdx]
      const hx = xOf(hp.time)
      const hy = yOf(hp.pct)
      ctx.strokeStyle = 'rgba(255,255,255,0.35)'
      ctx.lineWidth = 1
      ctx.setLineDash([3, 3])
      ctx.beginPath(); ctx.moveTo(hx, pad.top); ctx.lineTo(hx, h - pad.bottom); ctx.stroke()
      ctx.setLineDash([])
      ctx.beginPath(); ctx.arc(hx, hy, 5, 0, Math.PI * 2)
      ctx.fillStyle = color; ctx.fill()
      ctx.lineWidth = 2; ctx.strokeStyle = '#fff'; ctx.stroke()
      const label = `${hp.pct}%  ·  ${fmtDate(hp.time)}`
      ctx.font = '10px monospace'
      const tw = Math.ceil(ctx.measureText(label).width) + 12
      const th = 22
      let tx = hx - tw / 2
      tx = Math.max(pad.left, Math.min(tx, w - pad.right - tw))
      const ty = Math.max(pad.top, hy - th - 8)
      ctx.fillStyle = 'rgba(15,15,25,0.92)'
      ctx.strokeStyle = 'rgba(255,255,255,0.2)'
      ctx.lineWidth = 1
      ctx.beginPath()
      const r = 4
      ctx.moveTo(tx + r, ty)
      ctx.lineTo(tx + tw - r, ty); ctx.quadraticCurveTo(tx + tw, ty, tx + tw, ty + r)
      ctx.lineTo(tx + tw, ty + th - r); ctx.quadraticCurveTo(tx + tw, ty + th, tx + tw - r, ty + th)
      ctx.lineTo(tx + r, ty + th); ctx.quadraticCurveTo(tx, ty + th, tx, ty + th - r)
      ctx.lineTo(tx, ty + r); ctx.quadraticCurveTo(tx, ty, tx + r, ty)
      ctx.closePath()
      ctx.fill(); ctx.stroke()
      ctx.fillStyle = '#fff'
      ctx.textAlign = 'center'; ctx.textBaseline = 'middle'
      ctx.fillText(label, tx + tw / 2, ty + th / 2)
      ctx.textBaseline = 'alphabetic'
    }
  }, [points, isDischargeView, tab, hoverIdx])

  // ── Capacity degradation timeline chart ────────────────────────
  useEffect(() => {
    if (!capacityCanvasRef.current || capacityHistory.length < 2) return
    const canvas = capacityCanvasRef.current
    const ctx = canvas.getContext('2d')
    const dpr = window.devicePixelRatio || 1
    const w = canvas.clientWidth
    const h = canvas.clientHeight
    canvas.width = w * dpr
    canvas.height = h * dpr
    ctx.scale(dpr, dpr)
    ctx.clearRect(0, 0, w, h)

    const pad = { top: 8, right: 8, bottom: 8, left: 38 }
    const cw = w - pad.left - pad.right
    const ch = h - pad.top - pad.bottom
    const caps = capacityHistory.map(s => s.actualCapacityMah)
    const design = capacityHistory[0].designCapacityMah || Math.max(...caps)
    const yMin = Math.min(Math.min(...caps) - 30, Math.round(design * 0.6))
    const yMax = Math.max(Math.max(...caps) + 10, design)
    const minT = capacityHistory[0].time
    const maxT = capacityHistory[capacityHistory.length - 1].time
    const rangeT = (maxT - minT) || 1
    const xOf = (t) => pad.left + ((t - minT) / rangeT) * cw
    const yOf = (m) => pad.top + (1 - (m - yMin) / (yMax - yMin)) * ch

    ctx.font = '8px monospace'
    ctx.fillStyle = '#666'
    ctx.textAlign = 'right'
    ctx.fillText(`${design}`, pad.left - 4, yOf(design) + 3)
    ctx.fillText(`${yMin}mAh`, pad.left - 4, h - pad.bottom)
    ctx.strokeStyle = 'rgba(29,158,117,0.3)'
    ctx.setLineDash([3, 3])
    ctx.lineWidth = 1
    ctx.beginPath(); ctx.moveTo(pad.left, yOf(design)); ctx.lineTo(w - pad.right, yOf(design)); ctx.stroke()
    ctx.setLineDash([])

    ctx.strokeStyle = '#1D9E75'
    ctx.lineWidth = 1.5
    ctx.beginPath()
    capacityHistory.forEach((s, i) => {
      const x = xOf(s.time), y = yOf(s.actualCapacityMah)
      if (i === 0) ctx.moveTo(x, y)
      else ctx.lineTo(x, y)
    })
    ctx.stroke()

    capacityHistory.forEach(s => {
      ctx.beginPath(); ctx.arc(xOf(s.time), yOf(s.actualCapacityMah), 1.5, 0, Math.PI * 2)
      ctx.fillStyle = '#1D9E75'; ctx.fill()
    })
  }, [capacityHistory])

  // ── Chart pointer handlers ─────────────────────
  const handlePointer = useCallback((evt) => {
    if (!canvasRef.current || points.length < 2) return
    const rect = canvasRef.current.getBoundingClientRect()
    const clientX = evt.touches?.[0]?.clientX ?? evt.clientX
    if (clientX == null) return
    const x = clientX - rect.left
    const pad = { left: 35, right: 15 }
    const cw = rect.width - pad.left - pad.right
    const minT = points[0].time
    const maxT = points[points.length - 1].time
    const rangeT = maxT - minT || 1
    const t = minT + ((x - pad.left) / cw) * rangeT
    let lo = 0, hi = points.length - 1
    while (lo < hi) {
      const mid = (lo + hi) >> 1
      if (points[mid].time < t) lo = mid + 1
      else hi = mid
    }
    let idx = lo
    if (idx > 0 && Math.abs(points[idx - 1].time - t) < Math.abs(points[idx].time - t)) idx -= 1
    setHoverIdx(idx)
  }, [points])
  const clearHover = useCallback(() => setHoverIdx(-1), [])

  // ── Not on native: show message ────────────────
  if (!isNativePlatform) {
    return (
      <div className="card p-8 text-center space-y-4">
        <div className="text-4xl">📱</div>
        <h2 className="text-lg font-bold text-gray-200">Native Only</h2>
        <p className="text-xs text-gray-500">Battery Monitor requires the Android app to access hardware stats.</p>
      </div>
    )
  }

  return (
    <div className="space-y-4">
      {/* Header */}
      <div>
        <div className="flex items-center justify-between">
          <div className="text-[10px] tracking-[3px] text-gray-500">BATTERY MONITOR</div>
        </div>
        {stats && (
          <div className="text-[9px] text-gray-600 mt-0.5">
            {stats.deviceName || `${stats.deviceManufacturer} ${stats.deviceModel}`}
          </div>
        )}
      </div>

      {/* Battery gauge */}
      <div className="card p-5 flex items-center gap-5">
        <div className="relative w-16 h-16">
          <svg viewBox="0 0 36 36" className="w-16 h-16 -rotate-90">
            <path d="M18 2.0845 a 15.9155 15.9155 0 0 1 0 31.831 a 15.9155 15.9155 0 0 1 0 -31.831"
              fill="none" stroke="#1a1a2e" strokeWidth="3" />
            <path d="M18 2.0845 a 15.9155 15.9155 0 0 1 0 31.831 a 15.9155 15.9155 0 0 1 0 -31.831"
              fill="none" stroke={level > 20 ? '#1D9E75' : level > 10 ? '#E5A100' : '#E53E3E'}
              strokeWidth="3" strokeDasharray={`${level}, 100`} strokeLinecap="round" />
          </svg>
          <div className="absolute inset-0 flex items-center justify-center">
            <span className="text-lg font-bold text-gray-100">{stats ? `${level}%` : '—'}</span>
          </div>
        </div>
        <div className="flex-1">
          <div className="text-sm font-bold text-gray-200">
            {!stats ? 'Waiting...' : isCharging
              ? `Charging${stats?.chargeType && stats.chargeType !== 'None' ? `(${stats.chargeType})` : ''}`
              : 'On Battery'}
            {!isCharging && stats?.activeDischargeSessionId > 0 && (
              <span className="ml-2 text-[8px] text-amber-400 tracking-wider">TRACKING</span>
            )}
            {isCharging && stats?.activeChargeSessionId > 0 && (
              <span className="ml-2 text-[8px] text-accent-green tracking-wider">TRACKING</span>
            )}
          </div>
          {stats && (
            <div className="text-[10px] text-gray-500 mt-1 space-y-0.5">
              <div>
                {stats.health} · {stats.technology}
                {stats.chargingWatts > 0 && isCharging ? ` · ${stats.chargingWatts}W` : ''}
                {stats.chargeProtocol && isCharging ? ` · ${stats.chargeProtocol}` : ''}
                {isCharging && stats.negotiatedVoltageV > 0 && stats.chargeType !== 'Wireless' ? ` ${stats.negotiatedVoltageV}V` : ''}
              </div>
              <div>{stats.voltMv > 0 ? `${(stats.voltMv / 1000).toFixed(2)}V` : '—'} · {Math.abs(stats.currentMa)}mA · {stats.temperature}°C</div>
              {stats.chargeLimit < 100 && (
                <div className="text-blue-400">Charge limit: {stats.chargeLimit}%</div>
              )}
              {etaHrs > 0 && (
                <div className="text-[10px] text-gray-400">
                  {isCharging ? `${stats.chargeLimit < 100 ? stats.chargeLimit + '%' : 'Full'}` : 'Empty'} in ~{etaHrs >= 1 ? `${Math.floor(etaHrs)}h ${Math.round((etaHrs % 1) * 60)}m` : `${Math.round(etaHrs * 60)}m`}
                  {!isCharging && ` (${drainPctPerHr}%/hr)`}
                </div>
              )}
              {isCharging && etaHrs === 0 && stats.chargeLimit < 100 && level >= stats.chargeLimit && (
                <div className="text-green-400">At charge limit ({stats.chargeLimit}%)</div>
              )}
            </div>
          )}
        </div>
      </div>

      {/* Stats cards */}
      {stats && <div className="grid grid-cols-2 sm:grid-cols-4 gap-2">
        <StatCard label="Battery" value={`${level}%`} />
        <StatCard label="Status" value={
          !stats ? '—'
            : level >= (stats.chargeLimit || 100) && isCharging ? `Full (${stats.chargeLimit}%)`
            : isCharging ? `Charging(${stats.chargeType !== 'None' ? stats.chargeType : 'Wired'})`
            : 'Discharging'
        } />
        {!isCharging && <StatCard label="Drain Rate" value={drainPctPerHr > 0 ? `${drainPctPerHr}%/hr` : '—'} />}
        <StatCard label="ETA" value={etaHrs > 0 ? `${Math.floor(etaHrs)}h ${Math.round((etaHrs % 1) * 60)}m` : '—'} />
        <StatCard label="Current" value={`${Math.abs(stats.currentMa)}mA ${stats.currentMa >= 0 ? '↑' : '↓'}`} />
        <StatCard label="Voltage" value={stats.voltMv > 0 ? `${(stats.voltMv / 1000).toFixed(2)}V` : '—'} />
        {isCharging && stats.negotiatedVoltageV > 0 && stats.chargeType !== 'Wireless' && (
          <StatCard label="Negotiated" value={`${stats.negotiatedVoltageV}V · ${stats.negotiatedCurrentA}A`} />
        )}
        <StatCard label="Temp" value={`${stats.temperature}°C`} />
        <StatCard label="In Battery" value={stats.chargeMah > 0 ? `${stats.chargeMah}mAh` : '—'} />
        {isCharging && stats.chargingWatts > 0 && (
          <StatCard label="Max Power" value={`${stats.chargingWatts}W`} />
        )}
        <StatCard label="Sessions" value={`${chargeSessions.length} Charge / ${dischargeSessions.length} Discharge`} />
      </div>}

      {/* Usage permission prompt */}
      {stats && stats.hasUsagePermission === false && (
        <div className="card p-3 border border-amber-700/40 bg-amber-900/10">
          <div className="text-[10px] text-amber-300 font-semibold mb-1">USAGE ACCESS REQUIRED</div>
          <div className="text-[10px] text-gray-400 mb-2">
            App Usage + per-session drain breakdown need Android's "Usage Access" permission.
          </div>
          <button
            onClick={() => BatteryMonitor.requestUsagePermission({}).catch(() => {})}
            className="text-[10px] font-bold tracking-wider px-3 py-1.5 rounded-md border border-amber-500/50 text-amber-200 bg-amber-500/10 hover:bg-amber-500/20"
          >
            GRANT USAGE ACCESS →
          </button>
        </div>
      )}

      {/* Battery Health */}
      {stats && stats.actualCapacityMah > 0 && (
        <div className="card p-4">
          <div className="text-[9px] tracking-[2px] text-gray-500 mb-3">BATTERY HEALTH</div>
          <div className="grid grid-cols-2 gap-x-4 gap-y-2 text-[10px]">
            {stats.designCapacityMah > 0 && (
              <div>
                <div className="text-gray-600 text-[8px] tracking-wider">DESIGN CAPACITY</div>
                <div className="text-gray-400 font-mono">{stats.designCapacityMah}mAh</div>
              </div>
            )}
            <div>
              <div className="text-gray-600 text-[8px] tracking-wider">CHARGE CYCLES</div>
              <div className="text-gray-200 font-mono font-bold">{stats.cycleCount || '—'}</div>
            </div>
            <div>
              <div className="text-gray-600 text-[8px] tracking-wider">ACTUAL CAPACITY</div>
              <div className="text-gray-200 font-mono font-bold">{stats.actualCapacityMah}mAh</div>
            </div>
            {stats.ntSoH > 0 && stats.designCapacityMah > 0 && (
              <div>
                <div className="text-gray-600 text-[8px] tracking-wider">CAP (NOTHING SoH)</div>
                <div className="text-gray-200 font-mono font-bold">{Math.round(stats.designCapacityMah * stats.ntSoH / 100)}mAh</div>
              </div>
            )}
            {stats.healthPct >= 0 && (
              <div>
                <div className="text-gray-600 text-[8px] tracking-wider">HEALTH</div>
                <div className={`font-mono font-bold ${stats.healthPct >= 80 ? 'text-green-400' : stats.healthPct >= 60 ? 'text-amber-400' : 'text-red-400'}`}>
                  {stats.healthPct}%
                </div>
              </div>
            )}
            {stats.ntSoH > 0 && (
              <div>
                <div className="text-gray-600 text-[8px] tracking-wider">HEALTH (NOTHING SoH)</div>
                <div className={`font-mono font-bold ${stats.ntSoH >= 80 ? 'text-green-400' : stats.ntSoH >= 60 ? 'text-amber-400' : 'text-red-400'}`}>
                  {stats.ntSoH}%
                </div>
              </div>
            )}
          </div>
          {stats.healthPct >= 0 && stats.designCapacityMah > 0 && (
            <div className="mt-3">
              <div className="h-2 bg-surface-3 rounded-full overflow-hidden">
                <div className={`h-full rounded-full transition-all ${stats.healthPct >= 80 ? 'bg-green-500' : stats.healthPct >= 60 ? 'bg-amber-500' : 'bg-red-500'}`}
                  style={{ width: `${stats.healthPct}%` }} />
              </div>
              <div className="flex justify-between text-[8px] text-gray-600 mt-1">
                <span>Lost ~{stats.designCapacityMah - stats.actualCapacityMah}mAh ({(100 - stats.healthPct).toFixed(1)}%)</span>
                <span>{stats.cycleCount} cycles</span>
              </div>
            </div>
          )}

          {capacityHistory.length >= 2 && (
            <div className="mt-4 pt-3 border-t border-surface-3">
              <div className="flex items-center justify-between mb-2">
                <div className="text-[8px] tracking-[2px] text-gray-600">DEGRADATION TIMELINE</div>
                <div className="text-[8px] text-gray-600 font-mono">{capacityHistory.length} day{capacityHistory.length === 1 ? '' : 's'}</div>
              </div>
              <canvas ref={capacityCanvasRef} className="w-full" style={{ height: 80 }} />
              <div className="flex justify-between text-[8px] text-gray-600 mt-1 font-mono">
                <span>{fmtDate(capacityHistory[0].time)}</span>
                <span>{capacityHistory[capacityHistory.length - 1].actualCapacityMah} mAh today</span>
              </div>
            </div>
          )}
        </div>
      )}

      {/* Tabs */}
      <div className="flex gap-1 bg-surface-1 rounded-lg p-0.5">
        {TABS.map((t, i) => (
          <button key={t} onClick={() => setTab(i)}
            className={`flex-1 py-1.5 text-[9px] tracking-[2px] rounded-md transition-colors ${
              tab === i ? 'bg-surface-3 text-gray-200' : 'text-gray-500 hover:text-gray-400'
            }`}>{t.toUpperCase()}</button>
        ))}
      </div>

      {/* ═══ TAB 0: OVERVIEW ═══ */}
      {tab === 0 && (
        <>
          <div className="card p-3">
            <div className="text-[9px] tracking-[2px] text-gray-500 mb-2">
              {isDischargeView ? 'DISCHARGE CHART' : 'CHARGING CHART'}
            </div>
            {points.length >= 2 ? (
              <canvas
                ref={canvasRef}
                className="w-full touch-none cursor-crosshair"
                style={{ height: 200 }}
                onMouseMove={handlePointer}
                onMouseLeave={clearHover}
                onTouchStart={handlePointer}
                onTouchMove={handlePointer}
                onTouchEnd={clearHover}
              />
            ) : (
              <div className="flex items-center justify-center h-[120px] text-[10px] text-gray-600">
                Collecting data points... ~2 min while this page is open, ~30 min in background
              </div>
            )}
          </div>

          {points.length > 0 && (
            <div className="card overflow-hidden">
              <div className="px-4 py-2 border-b border-surface-3 text-[9px] tracking-[2px] text-gray-500">
                DATA LOG ({points.length} pts)
              </div>
              <div className="overflow-x-auto max-h-60 overflow-y-auto">
                <table className="w-full text-[10px]">
                  <thead className="sticky top-0 bg-surface-1">
                    <tr className="text-gray-500 border-b border-surface-3">
                      <th className="px-2 py-1.5 text-left font-medium">Time</th>
                      <th className="px-2 py-1.5 text-left font-medium">%</th>
                      <th className="px-2 py-1.5 text-left font-medium">+/-</th>
                      <th className="px-2 py-1.5 text-left font-medium">V</th>
                      <th className="px-2 py-1.5 text-left font-medium">mA</th>
                      <th className="px-2 py-1.5 text-left font-medium">°C</th>
                    </tr>
                  </thead>
                  <tbody>
                    {points.map((p, i) => {
                      const prev = i > 0 ? points[i - 1] : null
                      const dp = prev ? p.pct - prev.pct : 0
                      return (
                        <tr key={i} className="border-b border-surface-3/50 text-gray-400">
                          <td className="px-2 py-1 font-mono">{fmtTime(p.time)}</td>
                          <td className="px-2 py-1 font-mono">{p.pct}%</td>
                          <td className={`px-2 py-1 font-mono ${dp > 0 ? 'text-green-400' : dp < 0 ? 'text-red-400' : ''}`}>
                            {prev ? `${dp >= 0 ? '+' : ''}${dp}` : '—'}
                          </td>
                          <td className="px-2 py-1 font-mono">{p.voltMv ? (p.voltMv / 1000).toFixed(2) : '—'}</td>
                          <td className="px-2 py-1 font-mono">{p.currentMa ?? '—'}</td>
                          <td className="px-2 py-1 font-mono">{p.tempC ?? '—'}</td>
                        </tr>
                      )
                    })}
                  </tbody>
                </table>
              </div>
            </div>
          )}

          {sessions.length > 0 && (() => {
            const chargeCount = sessions.filter(s => (s.type || 'charge') === 'charge').length
            const dischargeCount = sessions.filter(s => s.type === 'discharge').length
            const filtered = sessionFilter === 'all'
              ? sessions
              : sessions.filter(s => (s.type || 'charge') === sessionFilter)
            const pillBase = 'px-2.5 py-1 rounded text-[9px] tracking-[1.5px] transition-colors cursor-pointer'
            return (
            <div className="card">
              <div className="px-4 py-2 border-b border-surface-3 flex items-center gap-2">
                <span className="text-[9px] tracking-[2px] text-gray-500">SESSIONS ({filtered.length}/{sessions.length})</span>
                <div className="ml-auto flex gap-1">
                  <button onClick={() => setSessionFilter('all')}
                    className={`${pillBase} ${sessionFilter === 'all' ? 'bg-surface-3 text-gray-200' : 'text-gray-500 hover:text-gray-300'}`}>
                    ALL {sessions.length}
                  </button>
                  <button onClick={() => setSessionFilter('charge')}
                    className={`${pillBase} ${sessionFilter === 'charge' ? 'bg-green-900/40 text-green-300' : 'text-gray-500 hover:text-green-400'}`}>
                    C {chargeCount}
                  </button>
                  <button onClick={() => setSessionFilter('discharge')}
                    className={`${pillBase} ${sessionFilter === 'discharge' ? 'bg-amber-900/40 text-amber-300' : 'text-gray-500 hover:text-amber-400'}`}>
                    D {dischargeCount}
                  </button>
                </div>
              </div>
              <div className="p-2 space-y-1 max-h-[28rem] overflow-y-auto">
                {filtered.map(s => {
                  const active = !s.endTime || s.endTime === 0
                  const dur = active ? Date.now() - s.startTime : s.endTime - s.startTime
                  const isSel = selectedSession === s.id
                  const isDischarge = s.type === 'discharge'
                  const signed = sessionSignedDeltaPct(s) ?? 0
                  const drain = Math.abs(signed)
                  const natural = isDischarge ? signed <= 0 : signed >= 0
                  const deltaColor = !natural ? 'text-amber-500' : (isDischarge ? 'text-amber-400' : 'text-green-400')
                  return (
                    <button key={s.id} onClick={() => loadSession(s.id)}
                      className={`w-full text-left px-3 py-2 rounded text-[10px] flex items-center gap-2 transition-colors ${
                        isSel ? (isDischarge ? 'bg-amber-500/10 border border-amber-500/30' : 'bg-accent-green/10 border border-accent-green/30') : 'hover:bg-surface-3/50'
                      }`}>
                      {active && <span className={`w-2 h-2 rounded-full animate-pulse shrink-0 ${isDischarge ? 'bg-amber-400' : 'bg-accent-green'}`} />}
                      <span className={`text-[8px] px-1.5 py-0.5 rounded ${isDischarge ? 'bg-amber-900/40 text-amber-300' : 'bg-green-900/40 text-green-300'}`}>
                        {isDischarge ? 'D' : 'C'}
                      </span>
                      <span className="font-mono text-gray-400">{fmtDate(s.startTime)}</span>
                      <span className="text-gray-600">{fmtDuration(dur)}</span>
                      {drain > 0 && <span className={`text-[9px] ml-auto font-mono ${deltaColor}`}>
                        {signed >= 0 ? '+' : '−'}{drain}%
                      </span>}
                      <span className="text-gray-600">{s.pointCount}pts</span>
                    </button>
                  )
                })}
                {filtered.length === 0 && (
                  <div className="text-center text-gray-600 text-[10px] py-4">No {sessionFilter} sessions</div>
                )}
              </div>
            </div>
            )
          })()}
        </>
      )}

      {/* ═══ TAB 1: DISCHARGE ═══ */}
      {tab === 1 && (
        <>
          {(() => {
            const viewingCharge = sessionType === 'charge'
            const deltaSign = sessionSignedDelta
            const anomaly = (isDischargeView && deltaSign > 0) || (viewingCharge && deltaSign < 0)
            const headerColor = isDischargeView ? 'border-amber-800/30' : 'border-accent-green/30'
            const tone = isDischargeView ? 'amber' : 'green'
            return (
          <div className={`card p-4 border ${headerColor}`}>
            <div className="flex items-center gap-2 mb-3">
              {((isDischargeView && stats?.activeDischargeSessionId > 0) ||
                (viewingCharge && stats?.activeChargeSessionId > 0)) && (
                <span className={`w-2 h-2 rounded-full bg-${tone}-400 animate-pulse`} />
              )}
              <span className={`text-[10px] tracking-[2px] text-${tone}-300`}>
                {isDischargeView
                  ? (stats?.activeDischargeSessionId > 0 ? 'DISCHARGE TRACKING ACTIVE' : 'DISCHARGE SESSION')
                  : (stats?.activeChargeSessionId > 0 ? 'CHARGE TRACKING ACTIVE' : 'CHARGE SESSION')}
              </span>
              {anomaly && (
                <span className="text-[8px] text-amber-500 ml-auto">Δ ANOMALY</span>
              )}
            </div>
            <div className="grid grid-cols-2 gap-2 text-[10px]">
              <div>
                <div className="text-gray-600 text-[8px] tracking-wider">
                  {isDischargeView ? 'SINCE UNPLUG' : 'SINCE PLUG-IN'}
                </div>
                <div className="text-gray-300 font-mono">{sessionDur > 0 ? fmtDuration(sessionDur) : '—'}</div>
              </div>
              <div>
                <div className="text-gray-600 text-[8px] tracking-wider">
                  {isDischargeView ? 'DRAINED' : 'GAINED'}
                </div>
                <div className={`text-${tone}-400 font-mono`}>
                  {sessionDrain > 0
                    ? `${deltaSign >= 0 ? '+' : '−'}${sessionDrain}%`
                    : '0%'}
                </div>
              </div>
              <div>
                <div className="text-gray-600 text-[8px] tracking-wider">
                  {isDischargeView ? 'DRAIN RATE' : 'CHARGE RATE'}
                </div>
                <div className="text-gray-300 font-mono">{drainPctPerHr > 0 ? `${drainPctPerHr}%/hr` : '—'}</div>
              </div>
              <div>
                <div className="text-gray-600 text-[8px] tracking-wider">
                  {isDischargeView ? 'EST. EMPTY IN' : 'EST. FULL IN'}
                </div>
                <div className="text-gray-300 font-mono">{etaHrs > 0 ? `${Math.floor(etaHrs)}h ${Math.round((etaHrs % 1) * 60)}m` : '—'}</div>
              </div>
            </div>
          </div>
            )
          })()}

          {sessionAppUsage.length > 0 && (
            <div className="card">
              <div className="px-4 py-2 border-b border-surface-3 text-[9px] tracking-[2px] text-gray-500">
                APP DRAIN {sessionDrain > 0 ? `(${sessionDrain}% total)` : ''}
              </div>
              <div className="p-3 space-y-2">
                {sessionAppUsage.map((app, i) => {
                  const fgMs = app.foregroundMs || 0
                  const fgsMs = app.foregroundServiceMs || 0
                  const totalMs = fgMs + fgsMs
                  const netBytes = (app.rxBytes || 0) + (app.txBytes || 0)
                  const mobileBytes = app.mobileBytes || 0
                  const scoreMs = totalMs + Math.floor(netBytes / 1024)
                  const totalScoreMs = sessionAppUsage.reduce((s, a) => {
                    const tm = (a.foregroundMs || 0) + (a.foregroundServiceMs || 0)
                    const nb = (a.rxBytes || 0) + (a.txBytes || 0)
                    return s + tm + Math.floor(nb / 1024)
                  }, 0)
                  const estDrain = totalScoreMs > 0 && sessionDrain > 0
                    ? ((scoreMs / totalScoreMs) * sessionDrain).toFixed(1)
                    : null
                  const fgsRatio = totalMs > 0 ? fgsMs / totalMs : 0
                  const netDominant = totalMs < 5000 && netBytes >= 1024 * 1024
                  return (
                    <div key={i}>
                      <div className="flex items-center justify-between text-[10px]">
                        <span className="text-gray-300 truncate max-w-[50%]">
                          {app.appName}
                          {fgsRatio > 0.5 && (
                            <span className="ml-1.5 text-[8px] text-cyan-400/80">BG</span>
                          )}
                          {netDominant && (
                            <span className="ml-1.5 text-[8px] text-violet-400/80">NET</span>
                          )}
                        </span>
                        <span className="text-gray-500 font-mono text-[9px]">
                          {totalMs > 0 ? fmtMs(totalMs) : ''}
                          {fgsMs > 0 && fgMs > 0 && (
                            <span className="text-cyan-400/70 ml-1">({fmtMs(fgsMs)} bg)</span>
                          )}
                          {netBytes > 0 && (
                            <span className="text-violet-400/70 ml-1">{fmtBytes(netBytes)}{mobileBytes > 0 ? ` · ${fmtBytes(mobileBytes)} 4G` : ''}</span>
                          )}
                          {estDrain && <span className="text-amber-400 ml-1">~{estDrain}%</span>}
                        </span>
                      </div>
                      <div className="h-1.5 bg-surface-3 rounded-full mt-1 overflow-hidden flex">
                        <div className="h-full bg-amber-500/60 transition-all"
                          style={{ width: `${totalScoreMs > 0 ? Math.min((fgMs / totalScoreMs) * 100, 100) : 0}%` }} />
                        <div className="h-full bg-cyan-500/60 transition-all"
                          style={{ width: `${totalScoreMs > 0 ? Math.min((fgsMs / totalScoreMs) * 100, 100) : 0}%` }} />
                        <div className="h-full bg-violet-500/60 transition-all"
                          style={{ width: `${totalScoreMs > 0 ? Math.min((Math.floor(netBytes / 1024) / totalScoreMs) * 100, 100) : 0}%` }} />
                      </div>
                    </div>
                  )
                })}
              </div>
            </div>
          )}

          {points.length >= 2 && (
            <div className="card p-3">
              <div className="text-[9px] tracking-[2px] text-gray-500 mb-2">
                {isDischargeView ? 'DISCHARGE CURVE' : 'CHARGE CURVE'}
              </div>
              <canvas
                ref={canvasRef}
                className="w-full touch-none cursor-crosshair"
                style={{ height: 200 }}
                onMouseMove={handlePointer}
                onMouseLeave={clearHover}
                onTouchStart={handlePointer}
                onTouchMove={handlePointer}
                onTouchEnd={clearHover}
              />
              <div className="text-[8px] text-gray-600 mt-2 text-center">Hover or drag to inspect any point</div>
            </div>
          )}

          {dischargeSessions.length > 0 && (
            <div className="card">
              <div className="px-4 py-2 border-b border-surface-3 text-[9px] tracking-[2px] text-gray-500">
                DISCHARGE HISTORY ({dischargeSessions.length})
              </div>
              <div className="p-2 space-y-1 max-h-48 overflow-y-auto">
                {dischargeSessions.map(s => {
                  const active = !s.endTime || s.endTime === 0
                  const dur = active ? Date.now() - s.startTime : s.endTime - s.startTime
                  const signed = sessionSignedDeltaPct(s) ?? 0
                  const drain = Math.abs(signed)
                  const anomaly = signed > 0
                  const isSel = selectedSession === s.id
                  return (
                    <button key={s.id} onClick={() => loadSession(s.id)}
                      className={`w-full text-left px-3 py-2 rounded text-[10px] flex items-center gap-3 transition-colors ${
                        isSel ? 'bg-amber-500/10 border border-amber-500/30' : 'hover:bg-surface-3/50'
                      }`}>
                      {active && <span className="w-2 h-2 rounded-full bg-amber-400 animate-pulse shrink-0" />}
                      <span className="font-mono text-gray-400">{fmtDate(s.startTime)}</span>
                      <span className="text-gray-600">{fmtDuration(dur)}</span>
                      {drain > 0 && <span className={`ml-auto font-mono ${anomaly ? 'text-amber-500' : 'text-amber-400'}`}>{signed >= 0 ? '+' : '−'}{drain}%</span>}
                      <span className="text-gray-600">{s.pointCount}pts</span>
                    </button>
                  )
                })}
              </div>
            </div>
          )}
        </>
      )}

      {/* ═══ TAB 2: APP USAGE ═══ */}
      {tab === 2 && (
        <div className="card">
          <div className="px-4 py-2 border-b border-surface-3 flex items-center justify-between">
            <span className="text-[9px] tracking-[2px] text-gray-500">APP USAGE · TODAY · IST</span>
            {appUsage?.apps?.length > 0 && (
              <span className="text-[8px] text-gray-600 font-mono">
                {appUsage.apps.length} apps · Σ {fmtMs(appUsage.apps.reduce((a, x) => a + (x.foregroundMs || 0) + (x.foregroundServiceMs || 0), 0))}
              </span>
            )}
          </div>
          {appUsage?.apps?.length > 0 ? (
            <div className="p-3 space-y-2.5">
              {appUsage.apps.map((app, i) => {
                const fgMs = app.foregroundMs || 0
                const fgsMs = app.foregroundServiceMs || 0
                const totalMs = fgMs + fgsMs
                const netBytes = (app.rxBytes || 0) + (app.txBytes || 0)
                const mobileBytes = app.mobileBytes || 0
                const scoreMs = totalMs + Math.floor(netBytes / 1024)
                const firstApp = appUsage.apps[0] || {}
                const firstScore = ((firstApp.foregroundMs || 0) + (firstApp.foregroundServiceMs || 0)) +
                  Math.floor(((firstApp.rxBytes || 0) + (firstApp.txBytes || 0)) / 1024)
                const pct = firstScore > 0 ? (scoreMs / firstScore) * 100 : 0
                const fgsRatio = totalMs > 0 ? fgsMs / totalMs : 0
                const netDominant = totalMs < 5000 && netBytes >= 1024 * 1024
                return (
                  <div key={i}>
                    <div className="flex items-center justify-between text-[10px]">
                      <span className="text-gray-300 truncate max-w-[55%]">
                        {app.appName}
                        {fgsRatio > 0.5 && (
                          <span className="ml-1.5 text-[8px] text-cyan-400/80">BG</span>
                        )}
                        {netDominant && (
                          <span className="ml-1.5 text-[8px] text-violet-400/80">NET</span>
                        )}
                      </span>
                      <div className="text-gray-500 font-mono text-[9px]">
                        {totalMs > 0 && <span className="text-gray-400">{fmtMs(totalMs)}</span>}
                        {fgsMs > 0 && fgMs > 0 && (
                          <span className="text-cyan-400/70 ml-1">({fmtMs(fgsMs)} bg)</span>
                        )}
                        {netBytes > 0 && (
                          <span className="text-violet-400/70 ml-1">{fmtBytes(netBytes)}{mobileBytes > 0 ? ` · ${fmtBytes(mobileBytes)} 4G` : ''}</span>
                        )}
                      </div>
                    </div>
                    <div className="h-1.5 bg-surface-3 rounded-full mt-1 overflow-hidden">
                      <div className="h-full bg-accent-green/50 rounded-full transition-all"
                        style={{ width: `${Math.min(pct, 100)}%` }} />
                    </div>
                  </div>
                )
              })}
            </div>
          ) : (
            <div className="p-4 text-center text-[10px] text-gray-600">
              {appUsage ? 'No usage data available' : 'Loading...'}
            </div>
          )}
        </div>
      )}
    </div>
  )
}

function StatCard({ label, value }) {
  return (
    <div className="card px-3 py-2.5">
      <div className="text-[8px] tracking-[2px] text-gray-600">{label.toUpperCase()}</div>
      <div className="text-sm font-bold text-gray-200 mt-0.5">{value}</div>
    </div>
  )
}
