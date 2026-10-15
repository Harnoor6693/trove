/**
 * The gate in front of every authenticated route.
 *
 * Branch order, and why each one exists:
 *
 *  1. `hydrating`      — hold. Deciding before `/v1/auth/me` answers is how a
 *                        valid cookie session gets bounced to /login.
 *  2. `hydrationError`  — a 5xx / network / CORS failure on the probe is neither
 *                        "logged out" nor "logged in": show a diagnostic rather
 *                        than a login loop that hides the real fault.
 *  3. `expired`        — a session existed and its refresh failed. /login is told
 *                        so, and says so. (Defect I4: an anonymous first visit
 *                        must never reach this branch.)
 *  4. `anonymous`      — nobody is logged in. Quiet redirect, no error copy.
 *  5. `/onboarding`    — always reachable once authenticated, so onboarding can
 *                        never bounce the user away from the page they need.
 *  6. preferences pending — hold rather than flash the wrong redirect.
 *  7. **preferences error — fail OPEN to a diagnostic, never to onboarding.**
 *                        This is defect I3: with no `isError` branch,
 *                        `prefs.data` is `undefined`, `!undefined?.onboardingCompleted`
 *                        is `true`, and a preferences outage was indistinguishable
 *                        from a first run — an already-onboarded user was sent
 *                        back through setup by a 500.
 *  8. not onboarded    — go finish setup.
 */
import { Navigate, useLocation } from 'react-router-dom'
import { useAuthStore } from '@stores/authStore'
import { usePreferences } from '@hooks/usePreferences'
import { Diagnostic } from '@components/Diagnostic/Diagnostic'

interface AuthGuardProps {
  children: React.ReactNode
}

function Loading({ label }: { label: string }) {
  return (
    <div role="status" aria-live="polite" data-testid="auth-loading">
      {label}
    </div>
  )
}

export function AuthGuard({ children }: AuthGuardProps) {
  const location = useLocation()
  const status = useAuthStore((s) => s.status)
  const hydrationError = useAuthStore((s) => s.hydrationError)
  const hydrate = useAuthStore((s) => s.hydrate)
  const prefs = usePreferences({ enabled: status === 'authenticated' })

  if (status === 'hydrating') return <Loading label="Checking your session…" />

  if (hydrationError) {
    return (
      <Diagnostic
        title="Trove can't reach its server"
        what="checking your session"
        error={hydrationError}
        onRetry={() => void hydrate()}
      />
    )
  }

  if (status === 'expired') return <Navigate to="/login?error=expired" replace />
  if (status !== 'authenticated') return <Navigate to="/login" replace />

  if (location.pathname.startsWith('/onboarding')) return <>{children}</>

  if (prefs.isPending) return <Loading label="Loading your settings…" />

  if (prefs.isError) {
    return (
      <Diagnostic
        title="Your settings didn't load"
        what="loading your preferences"
        error={prefs.error}
        onRetry={() => void prefs.refetch()}
      />
    )
  }

  if (!prefs.data?.onboardingCompleted) return <Navigate to="/onboarding" replace />

  return <>{children}</>
}
