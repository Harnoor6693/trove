/**
 * `/reset-password/:token` — the route that did not exist.
 *
 * Three things matter here and all three are recorded debts or contract facts:
 * the route is PUBLIC (a member following a reset link cannot sign in, so a
 * guarded route would be a dead link); `reset-invalid` covers unknown, expired
 * and already-used tokens with one identical answer; and a refused password
 * leaves the link usable, because the policy is checked before the token is
 * consumed.
 */
import { describe, expect, it } from 'vitest'
import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Route, Routes } from 'react-router-dom'
import { renderWithProviders } from '@/test/test-utils'
import { mswState, resetMswState } from '@/test/msw/handlers'
import { useAuthStore } from '@stores/authStore'
import { routes } from '@/router'
import ResetPassword from './index'

/**
 * The page reads its token from the PATH, so it has to be rendered behind a
 * matching route — rendering the component bare would hand `useParams` an empty
 * object and exercise the "incomplete link" branch instead.
 */
function renderAt(path: string) {
  return renderWithProviders(
    <Routes>
      <Route path="/reset-password/:token" element={<ResetPassword />} />
      <Route path="/login" element={<div>LOGIN PAGE</div>} />
    </Routes>,
    { initialPath: path }
  )
}

describe('Reset password', () => {
  it('is registered OUTSIDE AuthGuard, beside /login and /invite/:token', () => {
    // The guarded surface is the single `/` branch (plus `/onboarding`); every
    // public page is a sibling of it at the top level. A reset route nested
    // under the guard would bounce the very member it exists for.
    const top = routes.find((r) => r.path === '/reset-password/:token')
    expect(top).toBeDefined()
    expect(top).not.toHaveProperty('children')

    const guarded = routes.find((r) => r.path === '/')
    const guardedPaths = (guarded?.children ?? []).map((c) => c.path)
    expect(guardedPaths).not.toContain('reset-password/:token')
  })

  it('redeems a token without a session and lands the member at sign-in', async () => {
    resetMswState()
    // No session at all — the state a member following a reset link is in.
    useAuthStore.setState({ status: 'anonymous', user: null, hydrationError: null })
    const user = userEvent.setup()
    const { client } = renderAt('/reset-password/good-reset-token')

    await user.type(await screen.findByLabelText(/new password/i), 'a-long-enough-password')
    await user.click(screen.getByRole('button', { name: /set password/i }))

    expect(await screen.findByText(/your password is set/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /go to sign in/i })).toBeInTheDocument()
    await waitFor(() => expect(mswState.lastResetBody).not.toBeNull())
    expect(mswState.lastResetBody?.token).toBe('good-reset-token')

    // NEITHER half of this body survives the call. The password is obvious; the
    // token is the less obvious one and the worse one — it is a bearer
    // credential for setting this account's password until it expires, and
    // passed as an ordinary mutation variable it would sit in the mutation
    // cache, readable, for the gc window after this page unmounted.
    const serialised = JSON.stringify(
      client
        .getMutationCache()
        .getAll()
        .map((m) => m.state)
    )
    expect(serialised).not.toContain('a-long-enough-password')
    expect(serialised).not.toContain('good-reset-token')
    expect(document.body.innerHTML).not.toContain('a-long-enough-password')
    expect(document.body.innerHTML).not.toContain('good-reset-token')
  })

  it('shows one answer for an unknown, expired or used token — the route refuses to say which', async () => {
    resetMswState({ validResetToken: 'some-other-token' })
    const user = userEvent.setup()
    renderAt('/reset-password/good-reset-token')

    await user.type(await screen.findByLabelText(/new password/i), 'a-long-enough-password')
    await user.click(screen.getByRole('button', { name: /set password/i }))

    const alert = await screen.findByTestId('reset-error')
    expect(alert).toHaveTextContent(/no longer usable/i)
    expect(alert).toHaveTextContent(/expired, already been used, or never been valid/i)
  })

  it('a refused password says the link is still usable, because the policy is checked first', async () => {
    resetMswState()
    const user = userEvent.setup()
    renderAt('/reset-password/good-reset-token')

    await user.type(await screen.findByLabelText(/new password/i), 'short')
    await user.click(screen.getByRole('button', { name: /set password/i }))

    // The field says what was wrong with the password…
    expect(await screen.findByText('Use at least 12 characters.')).toBeInTheDocument()
    // …and the alert says what it means for the link, which is the part the
    // contract makes non-obvious: the policy is checked before the token is
    // consumed, so the link survives a refusal.
    expect(screen.getByTestId('reset-error')).toHaveTextContent(/reset link is still usable/i)
    // Still on the form: the member can try another password with the same link.
    expect(screen.getByLabelText(/new password/i)).toBeInTheDocument()
  })
})
