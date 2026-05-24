import { Suspense, lazy, useState, createContext, useContext } from 'react'
import { Routes, Route, Navigate } from 'react-router-dom'
import { isNativePlatform } from './firebase'
import { BatteryMonitor } from './utils/batteryPlugin'
import Layout from './components/Layout'

const Battery = lazy(() => import('./pages/Battery'))

export const AppContext = createContext(null)
export function useApp() { return useContext(AppContext) }

function PageFallback() {
  return (
    <div className="py-16 text-center text-[10px] tracking-[3px] text-gray-600 font-mono">
      LOADING...
    </div>
  )
}

export default function App() {
  const [toast, setToast] = useState('')

  // Battery retention sweep on startup
  if (isNativePlatform) {
    BatteryMonitor.purgeOldData({ retentionDays: 10 }).catch(() => {})
  }

  const showToast = (msg) => {
    setToast(msg)
    setTimeout(() => setToast(''), 2500)
  }

  const ctx = { user: null, showToast }

  return (
    <AppContext.Provider value={ctx}>
      {toast && (
        <div className="fixed bottom-24 md:bottom-6 left-1/2 -translate-x-1/2 bg-accent-green text-black px-5 py-2 rounded-full text-xs font-bold tracking-wider z-50 animate-pulse">
          {toast}
        </div>
      )}
      <Layout>
        <Suspense fallback={<PageFallback />}>
          <Routes>
            <Route path="/" element={<Battery />} />
            <Route path="*" element={<Navigate to="/" />} />
          </Routes>
        </Suspense>
      </Layout>
    </AppContext.Provider>
  )
}
