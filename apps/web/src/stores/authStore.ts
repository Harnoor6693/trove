/**
 * Session state for the SPA.
 *
 * The rule this store exists to enforce, from the product review in
 * `docs/recovery/04-REVIEWS-AND-SCOPE.md`: **an anonymous 401 is not an error.**
 * "No session ever existed" and "a session expired" are different facts that
 * produce different UI, so they are different states here rather than one
 * `user === null`:
 *
 *   hydrating     — the `/v1/auth/me` probe has not answered yet
 *   anonymous     — the probe 401'd; nobody is logged in; go to /login quietly
 *   authenticated — `user` is populated
 *   expired       — a session existed, its refresh failed; /login says so
 *
 * `hydrationError` is orthogonal: a 5xx / network / CORS failure on the probe is
 * neither of the three, and must surface a diagnostic instead of bouncing the
 * user around a login loop that hides the real fault.
 */
import { create } from 'zustand'
import { ApiError, setSessionExpiredHandler } from '@api/client'
import { getMe, logout as logoutRequest } from '@api/auth'
import type { MeResponse } from '@api/types'
import { queryClient } from '../queryClient'

export type AuthStatus = 'hydrating' | 'anonymous' | 'authenticated' | 'expired'

export interface AuthState {
  status: AuthStatus
  user: MeResponse | null
  hydrationError: ApiError | null
  hydrate: () => Promise<void>
  setUser: (user: MeResponse) => void
  markExpired: () => void
  logout: () => Promise<void>
}

/** Authenticated iff the status says so — one fact, one source. */
export const selectIsAuthenticated = (s: AuthState) => s.status === 'authenticated'
export const selectUser = (s: AuthState) => s.user

/**
 * Module-level coalescing so React StrictMode's double-invoked mount effect, and
 * any future racing caller, produce exactly one `/v1/auth/me` probe.
 */
let inflight: Promise<void> | null = null

export const useAuthStore = create<AuthState>()((set) => ({
  // Start hydrating, not anonymous: rendering <Navigate to="/login"/> before the
  // probe answers is how a valid cookie session gets bounced to the login page.
  status: 'hydrating',
  user: null,
  hydrationError: null,

  hydrate: async () => {
    if (inflight) return inflight
    inflight = (async () => {
      set({ status: 'hydrating', hydrationError: null })
      try {
        // skipAuthRefresh: this probe must never trigger a refresh. A first-time
        // visitor has no refresh cookie either, so refreshing would 401 too and
        // hard-redirect them to /login?error=expired before they ever had a
        // session to expire. That was defect I4.
        const user = await getMe({ skipAuthRefresh: true })
        set({ status: 'authenticated', user, hydrationError: null })
      } catch (err: unknown) {
        const apiError = err instanceof ApiError ? err : null
        if (apiError?.status === 401) {
          set({ status: 'anonymous', user: null, hydrationError: null })
          return
        }
        set({
          status: 'anonymous',
          user: null,
          hydrationError:
            apiError ??
            new ApiError({
              status: 0,
              isNetworkError: true,
              message: err instanceof Error ? err.message : String(err),
            }),
        })
      } finally {
        inflight = null
      }
    })()
    return inflight
  },

  setUser: (user) => set({ status: 'authenticated', user, hydrationError: null }),

  markExpired: () => set({ status: 'expired', user: null }),

  logout: async () => {
    try {
      await logoutRequest()
    } catch {
      // The cookie may already be invalid, or trove-core may be unreachable.
      // Either way the local session is over; leaving the SPA half-logged-in is
      // worse than a logout whose server call did not land.
    }
    set({ status: 'anonymous', user: null, hydrationError: null })
  },
}))

/**
 * Wire the HTTP client's "a real session just expired" signal into the store.
 * The client never imports the store (the store imports the client), and it never
 * navigates: `AuthGuard` renders the redirect from the `expired` state, so there
 * is no full page reload and the transition is testable.
 */
setSessionExpiredHandler(() => {
  useAuthStore.setState({ status: 'expired', user: null })
})

/**
 * Household data must not outlive the session that fetched it. Every way a
 * session ends (sign-out, expiry) passes through here, so the next person to
 * sign in on this tab starts with an empty cache.
 */
useAuthStore.subscribe((state, prev) => {
  if (
    prev.status === 'authenticated' &&
    (state.status === 'anonymous' || state.status === 'expired')
  ) {
    queryClient.clear()
  }
})

/** Test seam — resets the coalescing promise between cases. */
export function __resetAuthStore(): void {
  inflight = null
  useAuthStore.setState({ status: 'hydrating', user: null, hydrationError: null })
}
