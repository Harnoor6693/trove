import { describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { QueryClientProvider } from '@tanstack/react-query'
import { http } from 'msw'
import { server } from '@/test/mswServer'
import { DEFAULT_ME, fail, mswState, ok } from '@/test/msw/handlers'
import { makeQueryClient } from '@/test/test-utils'
import { ApiError } from '@api/client'
import { useAuthStore } from '@stores/authStore'
import { AuthGuard } from './AuthGuard'

function renderGuard(initialPath = '/') {
  const client = makeQueryClient()
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[initialPath]}>
        <Routes>
          <Route path="/login" element={<div>LOGIN PAGE</div>} />
          <Route path="/onboarding" element={<AuthGuard><div>ONBOARDING PAGE</div></AuthGuard>} />
          <Route path="/" element={<AuthGuard><div>PROTECTED CONTENT</div></AuthGuard>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>
  )
}

function authenticate() {
  useAuthStore.setState({ status: 'authenticated', user: { ...DEFAULT_ME }, hydrationError: null })
}

describe('AuthGuard', () => {
  it('holds while the session is still hydrating', () => {
    useAuthStore.setState({ status: 'hydrating' })
    renderGuard()
    expect(screen.getByTestId('auth-loading')).toBeInTheDocument()
  })

  it('shows a diagnostic — not /login — when the /me probe fails outright', () => {
    useAuthStore.setState({
      status: 'anonymous',
      user: null,
      hydrationError: new ApiError({ status: 500, message: 'boom', title: 'Server Error' }),
    })
    renderGuard()
    expect(screen.getByTestId('diagnostic')).toBeInTheDocument()
    expect(screen.queryByText('LOGIN PAGE')).not.toBeInTheDocument()
  })

  it('redirects an anonymous visitor to /login with no error copy', () => {
    useAuthStore.setState({ status: 'anonymous', user: null, hydrationError: null })
    renderGuard()
    expect(screen.getByText('LOGIN PAGE')).toBeInTheDocument()
  })

  it('redirects an expired session to /login?error=expired', () => {
    useAuthStore.setState({ status: 'expired', user: null, hydrationError: null })
    renderGuard()
    expect(screen.getByText('LOGIN PAGE')).toBeInTheDocument()
  })

  it('lets an authenticated user into /onboarding without consulting preferences', async () => {
    authenticate()
    mswState.preferences = { ...mswState.preferences, onboardingCompleted: false }
    renderGuard('/onboarding')
    expect(await screen.findByText('ONBOARDING PAGE')).toBeInTheDocument()
  })

  it('sends an un-onboarded user to /onboarding', async () => {
    authenticate()
    server.use(
      http.get('*/v1/me/preferences', () =>
        ok({ ...mswState.preferences, onboardingCompleted: false })
      )
    )
    renderGuard()
    expect(await screen.findByText('ONBOARDING PAGE')).toBeInTheDocument()
  })

  it('renders the app once preferences say onboarding is complete', async () => {
    authenticate()
    renderGuard()
    expect(await screen.findByText('PROTECTED CONTENT')).toBeInTheDocument()
  })

  it('fails OPEN to a diagnostic on a preferences error — never to onboarding (I3)', async () => {
    authenticate()
    server.use(
      http.get('*/v1/me/preferences', () => fail(500, 'internal-server-error', 'Server Error'))
    )
    renderGuard()
    expect(await screen.findByTestId('diagnostic')).toBeInTheDocument()
    // The exact defect: an outage used to be indistinguishable from a first run,
    // so an already-onboarded user got pushed back through setup by a 500.
    expect(screen.queryByText('ONBOARDING PAGE')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: /sign out/i })).toBeInTheDocument()
  })
})
