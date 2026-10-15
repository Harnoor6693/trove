/**
 * Account security — behaviour, not "it rendered".
 *
 * The two that matter most: the recovery codes are shown ONCE and are never
 * re-fetched (they are stored hashed, so a re-fetch is impossible and a client
 * that tries is a client that shows nothing), and no credential this screen
 * collects survives the call it was made for — not in component state, not in
 * the DOM, and not in TanStack Query's mutation cache, which keeps `variables`
 * alive for the gc window after the dialog closes.
 */
import { beforeEach, describe, expect, it } from 'vitest'
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithProviders } from '@/test/test-utils'
import { DEFAULT_ME, mswState, resetMswState } from '@/test/msw/handlers'
import { useAuthStore } from '@stores/authStore'
import { SecuritySection } from './SecuritySection'

function authenticate(over: Partial<typeof DEFAULT_ME> = {}) {
  useAuthStore.setState({
    status: 'authenticated',
    user: { ...DEFAULT_ME, ...over },
    hydrationError: null,
  })
}

beforeEach(() => {
  resetMswState()
  authenticate()
})

describe('Account security', () => {
  it('says the change keeps this session and ends the others, and never leaks the passwords', async () => {
    const user = userEvent.setup()
    const { client } = renderWithProviders(<SecuritySection />)

    await user.click(await screen.findByRole('button', { name: /change password/i }))
    const dialog = await screen.findByTestId('change-password-dialog')
    await user.type(screen.getByLabelText(/current password/i), 'correct-horse-battery')
    await user.type(screen.getByLabelText(/new password/i), 'a-brand-new-password')
    await user.click(within(dialog).getByRole('button', { name: /^change password$/i }))

    const done = await screen.findByTestId('password-changed')
    // The submit button that held focus is unmounted by the same commit that
    // mounts this panel, so without focus management a keyboard or screen
    // reader user is dropped on <body>, outside the dialog they are notionally
    // still inside.
    await waitFor(() => expect(done.contains(document.activeElement)).toBe(true))
    expect(done).toHaveTextContent(/still signed in here/i)
    expect(done).toHaveTextContent(/every other session on this account has been signed out/i)

    // Neither password is anywhere a later reader could find it.
    const mutations = client.getMutationCache().getAll()
    const serialised = JSON.stringify(mutations.map((m) => m.state))
    expect(serialised).not.toContain('correct-horse-battery')
    expect(serialised).not.toContain('a-brand-new-password')
    expect(document.body.innerHTML).not.toContain('correct-horse-battery')
    expect(document.body.innerHTML).not.toContain('a-brand-new-password')
  })

  it('shows a wrong current password as a refused credential, not as a generic failure', async () => {
    const user = userEvent.setup()
    renderWithProviders(<SecuritySection />)

    await user.click(await screen.findByRole('button', { name: /change password/i }))
    const dialog = await screen.findByTestId('change-password-dialog')
    await user.type(screen.getByLabelText(/current password/i), 'not-my-password')
    await user.type(screen.getByLabelText(/new password/i), 'a-brand-new-password')
    await user.click(within(dialog).getByRole('button', { name: /^change password$/i }))

    // 403 `step-up-required`, and the server's own words for it.
    const alerts = await screen.findAllByRole('alert')
    expect(alerts.some((a) => /that password was not accepted/i.test(a.textContent ?? ''))).toBe(true)
  })

  it('enrols in two steps and shows the recovery codes exactly once', async () => {
    const user = userEvent.setup()
    const { client } = renderWithProviders(<SecuritySection />)

    await user.click(await screen.findByRole('button', { name: /set up two-factor/i }))
    await user.type(screen.getByLabelText(/current password/i), 'correct-horse-battery')
    await user.click(screen.getByRole('button', { name: /continue/i }))

    // The secret is readable and the factor is explicitly NOT on yet.
    const enrolment = await screen.findByTestId('totp-enrolment')
    expect(enrolment).toHaveTextContent(/not on yet/i)
    expect(screen.getByTestId('totp-secret')).toHaveTextContent('AAAAAAAAAAAAAAAAAAAAAAAAAAAA')

    await user.type(screen.getByLabelText(/code from your authenticator/i), '123456')
    await user.click(screen.getByRole('button', { name: /turn on two-factor/i }))

    const codes = await screen.findByTestId('recovery-codes')
    // Focus follows the swap to the codes panel — this is the one moment the
    // content MUST be read and copied before it is gone for good, so being left
    // outside it on <body> is the worst place for a keyboard user to be.
    await waitFor(() => expect(codes.contains(document.activeElement)).toBe(true))
    expect(codes).toHaveTextContent('example-code-0001')
    expect(codes).toHaveTextContent(/only time trove can show you these/i)
    expect(mswState.totpConfirmCalls).toBe(1)

    // Dismissing them is the end of them: closing and reopening the section
    // does not go and fetch them again, because there is nothing to fetch.
    await user.click(screen.getByRole('button', { name: /i have saved them/i }))
    await waitFor(() => expect(screen.queryByTestId('recovery-codes')).not.toBeInTheDocument())
    expect(document.body.innerHTML).not.toContain('example-code-0001')
    expect(mswState.totpConfirmCalls).toBe(1)

    // "The last time they exist" has to be true of the mutation cache too: the
    // codes and the enrolment secret are RESPONSE data, which TanStack keeps in
    // the same entry as the request body it already refuses to store. Gone with
    // the dialog's observer, not five minutes after it.
    await waitFor(() => {
      const serialised = JSON.stringify(
        client
          .getMutationCache()
          .getAll()
          .map((m) => m.state)
      )
      expect(serialised).not.toContain('example-code-0001')
      expect(serialised).not.toContain('AAAAAAAAAAAAAAAAAAAAAAAAAAAA')
    })
  })

  it('disables through the POST twin and demands BOTH the password and a code', async () => {
    authenticate({ totpEnabled: true })
    const user = userEvent.setup()
    renderWithProviders(<SecuritySection />)

    await user.click(await screen.findByRole('button', { name: /turn off$/i }))
    const dialog = await screen.findByTestId('disable-totp-dialog')
    expect(dialog).toHaveTextContent(/both your password and a current code/i)

    // Password right, code wrong — still refused, which is the whole point.
    await user.type(screen.getByLabelText(/current password/i), 'correct-horse-battery')
    await user.type(screen.getByLabelText(/code from your authenticator/i), '000000')
    await user.click(screen.getByRole('button', { name: /turn off two-factor/i }))

    const alerts = await screen.findAllByRole('alert')
    expect(
      alerts.some((a) => /that password or code was not accepted/i.test(a.textContent ?? ''))
    ).toBe(true)
    expect(mswState.me.totpEnabled).toBe(false)
  })

  it('renders an unknown security-event kind rather than dropping the row', async () => {
    renderWithProviders(<SecuritySection />)
    const table = await screen.findByTestId('security-events-table')
    expect(table).toHaveTextContent('LOGIN_OK')
    // Not one of the kinds the schema's description lists — a client must
    // tolerate one, because the column is a plain string and later steps add to it.
    expect(table).toHaveTextContent('SOMETHING_NEW')
    expect(table).toHaveTextContent('not recorded')
  })
})
