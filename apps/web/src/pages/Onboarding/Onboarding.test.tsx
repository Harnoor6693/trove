import { describe, expect, it } from 'vitest'
import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithProviders } from '@/test/test-utils'
import { DEFAULT_ME, mswState } from '@/test/msw/handlers'
import { useAuthStore } from '@stores/authStore'
import Onboarding from './index'

function authenticate() {
  useAuthStore.setState({ status: 'authenticated', user: { ...DEFAULT_ME }, hydrationError: null })
}

describe('Onboarding', () => {
  it('has three steps, not six', async () => {
    authenticate()
    renderWithProviders(<Onboarding />)
    expect(await screen.findByText(/Step 1 of 3/)).toBeInTheDocument()
  })

  it('sets onboardingCompleted at the END of step 1', async () => {
    authenticate()
    mswState.preferences = { ...mswState.preferences, onboardingCompleted: false }
    const user = userEvent.setup()
    renderWithProviders(<Onboarding />)

    await user.click(await screen.findByRole('button', { name: /continue/i }))

    // From this moment AuthGuard can never redirect the user back into setup.
    await waitFor(() => expect(mswState.preferences.onboardingCompleted).toBe(true))
    expect(await screen.findByText(/Step 2 of 3/)).toBeInTheDocument()
  })

  it('submits the browser timezone and the chosen locale with step 1', async () => {
    authenticate()
    mswState.preferences = { ...mswState.preferences, onboardingCompleted: false, locale: 'en-CA' }
    const user = userEvent.setup()
    renderWithProviders(<Onboarding />)

    await user.click(await screen.findByRole('radio', { name: /US English/i }))
    await user.click(screen.getByRole('button', { name: /continue/i }))

    await waitFor(() => expect(mswState.preferences.locale).toBe('en-US'))
    expect(mswState.preferences.timezone).toBeTruthy()
  })

  it('shows the fixed reporting currency rather than offering to change it', async () => {
    authenticate()
    renderWithProviders(<Onboarding />)
    expect(await screen.findByTestId('onboarding-country')).toHaveTextContent('Canada')
    expect(screen.getByTestId('onboarding-country')).toHaveTextContent('CAD')
  })

  it('lets the user past the connect step without connecting anything (defect I2)', async () => {
    authenticate()
    const user = userEvent.setup()
    renderWithProviders(<Onboarding />)

    await user.click(await screen.findByRole('button', { name: /continue/i }))
    await screen.findByText(/Step 2 of 3/)

    // Two skip affordances plus an always-enabled Continue: no step in this flow
    // can be blocked by a paid third-party service any more.
    expect(screen.getAllByRole('button', { name: /skip for now/i })).toHaveLength(2)
    await user.click(screen.getByRole('button', { name: /^continue$/i }))
    expect(await screen.findByText(/Step 3 of 3/)).toBeInTheDocument()
  })

  it('offers Sign out on every step', async () => {
    authenticate()
    renderWithProviders(<Onboarding />)
    expect(await screen.findByRole('button', { name: /sign out/i })).toBeInTheDocument()
  })

  /**
   * The same single-use credential as the Accounts dialog, and therefore the
   * same rules: `useExchangeSetupTokenMutation`'s own contract is that no caller
   * echoes the token back to the screen or keeps it once the call settles. This
   * call site kept it in a plain text input for the rest of the session.
   */
  it('never renders the setup token in the clear, and drops it once the call settles', async () => {
    authenticate()
    const user = userEvent.setup()
    renderWithProviders(<Onboarding />)
    await user.click(await screen.findByRole('button', { name: /continue/i }))
    await screen.findByText(/Step 2 of 3/)

    const field = screen.getByLabelText(/setup token/i)
    expect(field).toHaveAttribute('type', 'password')
    expect(field).toHaveAttribute('autocomplete', 'off')

    await user.type(field, 'super-secret-token')
    await user.click(screen.getByRole('button', { name: /^connect$/i }))

    expect(await screen.findByRole('status')).toHaveTextContent(/Linked 1 account/)
    await waitFor(() => expect(field).toHaveValue(''))
    expect(document.body.textContent).not.toContain('super-secret-token')
  })

  it('shows no invented figures on the connect step', async () => {
    authenticate()
    const user = userEvent.setup()
    renderWithProviders(<Onboarding />)
    await user.click(await screen.findByRole('button', { name: /continue/i }))
    await screen.findByText(/Step 2 of 3/)
    expect(screen.getByTestId('onboarding-import-pending')).toBeInTheDocument()
  })

  /** F1-B3: there is no checklist on Today, so the last step must not promise one. */
  it('does not promise a Today checklist that does not exist (F1-B3)', async () => {
    authenticate()
    const user = userEvent.setup()
    renderWithProviders(<Onboarding />)
    await user.click(await screen.findByRole('button', { name: /continue/i }))
    await screen.findByText(/Step 2 of 3/)
    await user.click(screen.getByRole('button', { name: /^continue$/i }))
    await screen.findByText(/Step 3 of 3/)
    expect(document.body.textContent).not.toMatch(/Today screen as a checklist/i)
  })
})
