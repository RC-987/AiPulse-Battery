import { useEffect, useRef } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { App as CapacitorApp } from '@capacitor/app'
import { isNativePlatform } from '../firebase'

export default function Layout({ children }) {
  const location = useLocation()
  const navigate = useNavigate()
  const pathnameRef = useRef(location.pathname)

  useEffect(() => {
    pathnameRef.current = location.pathname
  }, [location.pathname])

  useEffect(() => {
    if (!isNativePlatform) return

    let handle

    const registerBackHandler = async () => {
      handle = await CapacitorApp.addListener('backButton', async () => {
        const currentPath = pathnameRef.current
        if (currentPath === '/') {
          await CapacitorApp.minimizeApp()
          return
        }
        navigate('/', { replace: true })
      })
    }

    registerBackHandler()
    return () => { handle?.remove() }
  }, [navigate])

  return (
    <div className="min-h-screen bg-surface-0 pb-8">
      <header className="bg-surface-1 border-b border-surface-3 sticky top-0 z-40">
        <div className="max-w-4xl mx-auto px-4 py-3 flex items-center justify-between">
          <span className="text-xl font-light tracking-tight relative inline-flex items-center">
            <span className="text-accent-green font-semibold">⚡</span>
            <span className="ml-2 text-sm font-bold tracking-[3px] text-gray-300">BATTERY MONITOR</span>
          </span>
        </div>
      </header>

      <main className="max-w-4xl mx-auto px-4 py-5">
        {children}
      </main>
    </div>
  )
}
