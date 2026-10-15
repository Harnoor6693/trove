/**
 * The two ways into the second-factor code form, and the recorded blocker that
 * only one of them used to work.
 *
 * A password login hands the client a `challengeToken`. An OIDC sign-in by a
 * member with TOTP enabled never goes through `POST /v1/auth/login` at all: the
 * server redirects the browser to `<publicUrl>/login?error=totp_required` and
 * sets the HttpOnly `__Host-trove_totp_challenge` cookie, which the SPA cannot
 * read. The page must render the code form on that query parameter alone and
 * post `{code}` with NO `challengeToken` — the field is optional in
 * `TotpLoginRequest` precisely so the cookie can carry it.
 */
import { describe, expect, it } from 'vitest'
import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithProviders } from '@/test/test-utils'
import { mswState, resetMswState } from '@/test/msw/handlers'
import { ERROR_MESSAGES } from './errorCopy'
import Login from './index'

describe('Login — the second factor', () => {
  it('password login: renders the code form from the challenge token and sends it', async () => {
    resetMswState({ totpEnabled: true })
    const user = userEvent.setup()
    renderWithProviders(<Login />, { initialPath: '/login' })

    await user.type(await screen.findByLabelText(/email/i), 'a@b.co')
    await user.type(screen.getByLabelText(/^password$/i), 'right')
    await user.click(screen.getByRole('button', { name: /sign in/i }))

    await user.type(await screen.findByLabelText(/authentication code/i), '123456')
    await user.click(screen.getByRole('button', { name: /verify/i }))

    await waitFor(() => expect(mswState.lastTotpLoginBody).not.toBeNull())
    expect(mswState.lastTotpLoginBody).toEqual({ challengeToken: 'challenge-token', code: '123456' })
  })

  it('OIDC return: `?error=totp_required` alone renders the code form and posts `{code}` with no token', async () => {
    const user = userEvent.setup()
    renderWithProviders(<Login />, { initialPath: '/login?error=totp_required' })

    // No password login happened, so the SPA holds no challenge token at all.
    const codeInput = await screen.findByLabelText(/authentication code/i)
    expect(codeInput).toBeInTheDocument()
    expect(screen.queryByLabelText(/^password$/i)).not.toBeInTheDocument()

    // It is an instruction, not a failure: a status, never an alert.
    expect(screen.getByRole('status')).toHaveTextContent(ERROR_MESSAGES.totp_required)
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()

    await user.type(codeInput, '123456')
    await user.click(screen.getByRole('button', { name: /verify/i }))

    await waitFor(() => expect(mswState.lastTotpLoginBody).not.toBeNull())
    // The whole point: the key is ABSENT, not null and not an empty string.
    expect(mswState.lastTotpLoginBody).toEqual({ code: '123456' })
    expect(mswState.lastTotpLoginBody).not.toHaveProperty('challengeToken')
  })

  it('a refused code on the OIDC path reads as a refusal, not as the instruction', async () => {
    const user = userEvent.setup()
    renderWithProviders(<Login />, { initialPath: '/login?error=totp_required' })

    await user.type(await screen.findByLabelText(/authentication code/i), '000000')
    await user.click(screen.getByRole('button', { name: /verify/i }))

    expect(await screen.findByRole('alert')).toHaveTextContent(ERROR_MESSAGES.totp_invalid)
  })

  it('links the OIDC entry point the server advertised, not the hardcoded constant', async () => {
    resetMswState({ oidcEnabled: true })
    renderWithProviders(<Login />, { initialPath: '/login' })

    const link = await screen.findByRole('link', { name: /continue with/i })
    expect(link).toHaveAttribute('href', 'https://trove.test/api/v1/auth/start')
  })

  it('points a member with a forgotten password at an administrator, because minting is admin-only', async () => {
    renderWithProviders(<Login />, { initialPath: '/login' })
    expect(await screen.findByText(/ask an administrator of your household for a reset link/i)).toBeInTheDocument()
    // `POST /v1/auth/password/reset-request` requires the ADMIN role, so there
    // is deliberately no self-service request form on this page.
    expect(screen.queryByRole('button', { name: /email me a link|reset my password/i })).not.toBeInTheDocument()
  })
})
