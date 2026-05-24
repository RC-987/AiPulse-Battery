import { Component } from 'react'

export default class ErrorBoundary extends Component {
  state = { hasError: false, error: null }

  static getDerivedStateFromError(error) {
    return { hasError: true, error }
  }

  componentDidCatch(error, info) {
    console.error('App crash:', error, info)
  }

  render() {
    if (this.state.hasError) {
      return (
        <div className="min-h-screen bg-surface-0 flex items-center justify-center px-4">
          <div className="card p-8 max-w-lg w-full text-center space-y-4">
            <div className="text-4xl opacity-40">⚠️</div>
            <h1 className="text-xl font-bold text-red-400">Something went wrong</h1>
            <p className="text-xs text-gray-400 break-words">{this.state.error?.message}</p>
            <button
              onClick={() => window.location.reload()}
              className="btn btn-primary py-2.5 px-6 text-[10px] tracking-[2px]"
            >
              RELOAD APP
            </button>
          </div>
        </div>
      )
    }

    return this.props.children
  }
}
