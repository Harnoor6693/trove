/**
 * The screen every "we cannot tell what is wrong" path lands on.
 *
 * The rule it exists for (product review, `04-REVIEWS-AND-SCOPE.md`): a failure
 * must never be indistinguishable from a first run, and must never leave the user
 * without an exit. So this names the failing call, shows the server's
 * `requestId` when there is one so a log line can be found, and always offers
 * Retry and Sign out.
 */
import { QlCard } from '@components/ui/QlCard/QlCard'
import { QlButton } from '@components/ui/QlButton/QlButton'
import { useLogout } from '@hooks/useLogout'
import { ApiError } from '@api/client'
import styles from './Diagnostic.module.css'

export interface DiagnosticProps {
  title: string
  /** What the SPA was doing, e.g. "loading your preferences". */
  what: string
  error?: unknown
  onRetry?: () => void
}

function describe(error: unknown): { detail: string; requestId: string | null; status: number | null } {
  if (error instanceof ApiError) {
    return {
      detail: error.isNetworkError
        ? 'The server did not respond. It may still be starting, or the address may be wrong.'
        : (error.detail ?? error.title ?? error.message),
      requestId: error.requestId,
      status: error.status || null,
    }
  }
  if (error instanceof Error) return { detail: error.message, requestId: null, status: null }
  return { detail: 'No further detail was returned.', requestId: null, status: null }
}

export function Diagnostic({ title, what, error, onRetry }: DiagnosticProps) {
  const logout = useLogout()
  const { detail, requestId, status } = describe(error)

  return (
    <div className={styles.page} role="alert" data-testid="diagnostic">
      <QlCard>
        <div className={styles.card}>
          <h1 className={styles.title}>{title}</h1>
          <p className={styles.detail}>
            Trove could not finish {what}. Your data is untouched — this is a connection or
            server problem, not a sign that setup is incomplete.
          </p>
          <p className={styles.detail}>{detail}</p>
          {(status || requestId) && (
            <p className={styles.meta}>
              {status ? `HTTP ${status}` : null}
              {status && requestId ? ' · ' : null}
              {requestId ? `request ${requestId}` : null}
            </p>
          )}
          <div className={styles.actions}>
            {onRetry && (
              <QlButton variant="primary" onClick={onRetry}>
                Try again
              </QlButton>
            )}
            <QlButton variant="ghost" onClick={() => void logout()}>
              Sign out
            </QlButton>
          </div>
        </div>
      </QlCard>
    </div>
  )
}
