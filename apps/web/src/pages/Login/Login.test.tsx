import { describe, expect, it } from 'vitest'
import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithProviders } from '@/test/test-utils'
import { mswState } from '@/test/msw/handlers'
import { useAuthStore } from '@stores/authStore'
import Login from './index'
import { ERROR_MESSAGES } from './errorCopy'

describe('Login', () => {
  it('renders an email/password form, not an Authentik redirect', async () => {
    renderWithProviders(<Login />, { initialPath: '/login' })
    expect(await screen.findByLabelText(/email/i)).toBeInTheDocument()
    expect(screen.getByLabelText(/password/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /sign in/i })).toBeInTheDocument()
  })

  it('names the expired session instead of falling through to a generic message (I4)', async () => {
    renderWithProviders(<Login />, { initialPath: '/login?error=expired' })
    expect(await screen.findByRole('alert')).toHaveTextContent(ERROR_MESSAGES.expired)
  })

  it('says the credentials did not match on a 401', async () => {
    const user = userEvent.setup()
    renderWithProviders(<Login />, { initialPath: '/login' })
    await user.type(await screen.findByLabelText(/email/i), 'a@b.co')
    await user.type(screen.getByLabelText(/password/i), 'wrong')
    await user.click(screen.getByRole('button', { name: /sign in/i }))
    expect(await screen.findByRole('alert')).toHaveTextContent(ERROR_MESSAGES.invalid)
  })

  it('says "too many attempts" on a 429 — the only way `locked` is reachable', async () => {
    const user = userEvent.setup()
    renderWithProviders(<Login />, { initialPath: '/login' })
    await user.type(await screen.findByLabelText(/email/i), 'a@b.co')
    await user.type(screen.getByLabelText(/password/i), 'ratelimited')
    await user.click(screen.getByRole('button', { name: /sign in/i }))
    expect(await screen.findByRole('alert')).toHaveTextContent(ERROR_MESSAGES.locked)
  })

  it('steps up to a TOTP screen when the server returns a challenge', async () => {
    mswState.totpEnabled = true
    const user = userEvent.setup()
    renderWithProviders(<Login />, { initialPath: '/login' })
    await user.type(await screen.findByLabelText(/email/i), 'a@b.co')
    await user.type(screen.getByLabelText(/password/i), 'correct-horse-battery')
    await user.click(screen.getByRole('button', { name: /sign in/i }))

    const code = await screen.findByLabelText(/authentication code/i)
    expect(code).toBeInTheDocument()
    await user.type(code, '000000')
    await user.click(screen.getByRole('button', { name: /verify/i }))
    expect(await screen.findByRole('alert')).toHaveTextContent(ERROR_MESSAGES.totp_invalid)

    await user.clear(screen.getByLabelText(/authentication code/i))
    await user.type(screen.getByLabelText(/authentication code/i), '123456')
    await user.click(screen.getByRole('button', { name: /verify/i }))
    await waitFor(() => expect(useAuthStore.getState().status).toBe('authenticated'))
  })

  it('hides the OIDC button when no provider is configured', async () => {
    renderWithProviders(<Login />, { initialPath: '/login' })
    await screen.findByLabelText(/email/i)
    expect(screen.queryByText(/continue with/i)).not.toBeInTheDocument()
  })

  it('shows the OIDC button with the provider name when one is configured', async () => {
    mswState.oidcEnabled = true
    renderWithProviders(<Login />, { initialPath: '/login' })
    expect(await screen.findByText(/continue with authentik/i)).toBeInTheDocument()
  })

  it('says the install is invite-only when registration is closed', async () => {
    renderWithProviders(<Login />, { initialPath: '/login' })
    expect(await screen.findByText(/invite-only/i)).toBeInTheDocument()
  })

  it('links to the source code under the form (AGPL-3.0 §13, step 28)', async () => {
    renderWithProviders(<Login />, { initialPath: '/login' })
    const link = await screen.findByRole('link', { name: 'Source code' })
    expect(link).toHaveAttribute('href', 'https://github.com/Harnoor6693/trove')
    expect(link).toHaveAttribute('target', '_blank')
    expect(link.getAttribute('rel') ?? '').toContain('noopener')
    expect(screen.getByText(/free software under the AGPL-3\.0/i)).toBeInTheDocument()
  })

  it('offers sign-up only when registration is open', async () => {
    mswState.registrationOpen = true
    renderWithProviders(<Login />, { initialPath: '/login' })
    expect(await screen.findByRole('link', { name: /create one/i })).toBeInTheDocument()
  })
})
