import { Component, type ErrorInfo, type ReactNode } from 'react'
import { QlButton } from '@components/ui/QlButton/QlButton'
import { QlCard } from '@components/ui/QlCard/QlCard'
import styles from './ErrorBoundary.module.css'

interface Props {
  children: ReactNode
  label?: string
}

interface State {
  hasError: boolean
  resetKey: number
  /** While true we render null so that a parent rerender can swap in safe children. */
  resetting: boolean
}

export class ErrorBoundary extends Component<Props, State> {
  state: State = { hasError: false, resetKey: 0, resetting: false }

  static getDerivedStateFromError(): Partial<State> {
    return { hasError: true, resetting: false }
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    // Surface to console for local dev; telemetry wiring is a follow-up slice.
    // No logger utility exists in this codebase yet — this is the sole error
    // reporting site for render-tree crashes, so it must not be silenced.
    // eslint-disable-next-line no-console
    console.error('[ErrorBoundary]', this.props.label ?? '', error, info.componentStack)
  }

  componentDidUpdate(_prevProps: Props, prevState: State) {
    // Once resetting is true and children have been updated by the parent
    // (via rerender), clear the resetting flag so children render fresh.
    if (prevState.resetting && this.state.resetting) {
      this.setState({ resetting: false })
    }
  }

  retry = () =>
    this.setState((s) => ({
      hasError: false,
      resetKey: s.resetKey + 1,
      resetting: true,
    }))

  render() {
    if (this.state.hasError) {
      return (
        <QlCard>
          <div className={styles.fallback}>
            <div className={styles.message}>
              Couldn't load{this.props.label ? ` ${this.props.label}` : ''}.
            </div>
            <QlButton variant="ghost" onClick={this.retry}>Retry</QlButton>
          </div>
        </QlCard>
      )
    }

    // Render null briefly after retry so the parent can provide safe children
    // before we attempt to render them (avoids re-catching the stale throw).
    if (this.state.resetting) {
      return null
    }

    // The `key` forces a fresh subtree on retry, so children whose hooks
    // hold the failed state remount cleanly rather than reusing them.
    return <div key={this.state.resetKey}>{this.props.children}</div>
  }
}
