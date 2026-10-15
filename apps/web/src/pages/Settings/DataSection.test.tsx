/**
 * Your data — the export and the self-deletion.
 *
 * The behaviours that had to be proved: the step-up body never survives its
 * call; the export polls to COMPLETE and only then offers a download; the
 * download goes through the authenticated client and revokes its object URL; a
 * second export the same day shows the server's own 409 with its timing rather
 * than a client-invented timer; the deletion uses the POST twin; and the
 * last-admin 409 reads as an explanation, not as a failure.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Route, Routes } from 'react-router-dom'
import { renderWithProviders } from '@/test/test-utils'
import { DEFAULT_ME, mswState, resetMswState } from '@/test/msw/handlers'
import { useAuthStore } from '@stores/authStore'
import { DataSection } from './DataSection'

function authenticate(over: Partial<typeof DEFAULT_ME> = {}) {
  useAuthStore.setState({
    status: 'authenticated',
    user: { ...DEFAULT_ME, ...over },
    hydrationError: null,
  })
}

beforeEach(() => {
  let n = 0
  URL.createObjectURL = vi.fn(() => `blob:trove/${(n += 1)}`)
  URL.revokeObjectURL = vi.fn()
  resetMswState()
  authenticate()
})

describe('Your data — export', () => {
  it('says what the archive holds and, specifically, that receipts are metadata only', async () => {
    const user = userEvent.setup()
    renderWithProviders(<DataSection />)

    await user.click(await screen.findByRole('button', { name: /export everything/i }))
    const dialog = await screen.findByTestId('start-export-dialog')
    expect(dialog).toHaveTextContent(/receipts are included as details only, never as images/i)
    expect(dialog).toHaveTextContent(/your household mates' rows too/i)
    expect(dialog).toHaveTextContent(/nothing is converted and no total is blended across currencies/i)
    expect(dialog).toHaveTextContent(/one export a day/i)
  })

  it('polls a started run to COMPLETE, then downloads it through the session and revokes the URL', async () => {
    resetMswState({ dataExportPollsRemaining: 0 })
    authenticate()
    const user = userEvent.setup()
    renderWithProviders(<DataSection />)

    await user.click(await screen.findByRole('button', { name: /export everything/i }))
    const dialog = await screen.findByTestId('start-export-dialog')
    await user.type(within(dialog).getByLabelText(/your password/i), 'correct-horse-battery')
    await user.click(within(dialog).getByRole('button', { name: /start export/i }))

    const table = await screen.findByTestId('exports-table')
    await waitFor(() => expect(table).toHaveTextContent('Ready'), { timeout: 10000 })

    // The bytes need the caller's own credentials, so the control is a button
    // and there is no anchor to the API path anywhere on the screen.
    expect(screen.queryByRole('link', { name: /download/i })).not.toBeInTheDocument()

    const clicks = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
    await user.click(within(table).getByRole('button', { name: 'Download' }))

    await waitFor(() => expect(URL.createObjectURL).toHaveBeenCalled())
    expect(clicks).toHaveBeenCalled()
    await waitFor(() => expect(URL.revokeObjectURL).toHaveBeenCalled())
    clicks.mockRestore()
  }, 20000)

  it('shows the server’s own 409 timing for a second export the same day', async () => {
    resetMswState({
      dataExportDailyCap: 'You can start another export after 2026-09-19 00:00 in your own time zone.',
    })
    authenticate()
    const user = userEvent.setup()
    renderWithProviders(<DataSection />)

    await user.click(await screen.findByRole('button', { name: /export everything/i }))
    const dialog = await screen.findByTestId('start-export-dialog')
    await user.type(within(dialog).getByLabelText(/your password/i), 'correct-horse-battery')
    await user.click(within(dialog).getByRole('button', { name: /start export/i }))

    // The server is the only thing that knows when — the client invents nothing.
    expect(await screen.findByTestId('export-error')).toHaveTextContent(
      'You can start another export after 2026-09-19 00:00 in your own time zone.'
    )
  })

  it('reads a 409 on download as "not ready", never as a failure', async () => {
    resetMswState({
      dataExportPollsRemaining: 0,
      dataExportDownloadNotReady: true,
      dataExports: [
        {
          id: 'export-ready',
          userId: DEFAULT_ME.userId,
          status: 'COMPLETE',
          fileSizeBytes: 20480,
          errorMessage: null,
          expiresAt: new Date(Date.now() + 86_400_000).toISOString(),
          createdAt: new Date().toISOString(),
          updatedAt: new Date().toISOString(),
        },
      ],
    })
    authenticate()
    const user = userEvent.setup()
    renderWithProviders(<DataSection />)

    const table = await screen.findByTestId('exports-table')
    await user.click(within(table).getByRole('button', { name: 'Download' }))

    const notReady = await screen.findByTestId('download-not-ready')
    expect(notReady).toHaveAttribute('role', 'status')
    expect(notReady).toHaveTextContent(/not ready to download/i)
    expect(screen.queryByTestId('download-error')).not.toBeInTheDocument()
  })

  it('never lets the step-up credentials outlive the call', async () => {
    resetMswState()
    authenticate({ totpEnabled: true })
    const user = userEvent.setup()
    const { client } = renderWithProviders(<DataSection />)

    await user.click(await screen.findByRole('button', { name: /export everything/i }))
    const dialog = await screen.findByTestId('start-export-dialog')
    await user.type(within(dialog).getByLabelText(/your password/i), 'correct-horse-battery')
    await user.type(within(dialog).getByLabelText(/code from your authenticator/i), '123456')
    await user.click(within(dialog).getByRole('button', { name: /start export/i }))

    await waitFor(() => expect(mswState.lastStepUpKeys).toEqual(['code', 'password']))

    // The mutation cache is where an ordinary `mutate(body)` would have left
    // them, readable for the gc window after the dialog closed.
    const serialised = JSON.stringify(client.getMutationCache().getAll().map((m) => m.state))
    expect(serialised).not.toContain('correct-horse-battery')
    expect(serialised).not.toContain('123456')
    expect(document.body.innerHTML).not.toContain('correct-horse-battery')
  })
})

describe('Your data — deleting your own account', () => {
  it('states the consequences honestly before it asks for anything', async () => {
    const user = userEvent.setup()
    renderWithProviders(<DataSection />)

    await user.click(await screen.findByRole('button', { name: /delete my account…/i }))
    const dialog = await screen.findByTestId('delete-account-dialog')

    expect(dialog).toHaveTextContent(/disabled the moment you confirm/i)
    expect(dialog).toHaveTextContent(/every session you have anywhere ends at once/i)
    expect(dialog).toHaveTextContent(/administrator of your household can bring it back for seven days/i)
    expect(dialog).toHaveTextContent(/erased permanently/i)
    // The household keeps the shared ledger — a settled owner decision.
    expect(dialog).toHaveTextContent(/the shared ledger/i)
    expect(dialog).toHaveTextContent(/your name removed from the rows/i)
    expect(dialog).toHaveTextContent(/email address stays reserved/i)
    // Restore is the ADMIN's route: no self-restore affordance is offered.
    expect(within(dialog).queryByRole('button', { name: /restore/i })).not.toBeInTheDocument()
    // The safe action is the primary one; the destructive one says what it does.
    expect(within(dialog).getByRole('button', { name: /keep my account/i })).toBeInTheDocument()
    expect(within(dialog).getByRole('button', { name: /delete my account$/i })).toBeInTheDocument()
  })

  it('deletes through POST /v1/me/delete, not the DELETE twin, and clears the local session', async () => {
    const user = userEvent.setup()
    // Rendered behind a route rather than bare, because the thing under test is
    // what the deletion leaves behind AFTER it sends the member to `/login` —
    // and `/login` is a public route of this same SPA instance, reached without
    // a reload, with the same module-level query client still in memory.
    const { client } = renderWithProviders(
      <Routes>
        <Route path="/settings" element={<DataSection />} />
        <Route path="/login" element={<div>LOGIN PAGE</div>} />
      </Routes>,
      { initialPath: '/settings' }
    )

    // Household financial data fetched while signed in, exactly as any other
    // screen would have left it behind. Whatever survives here is what the next
    // person to sign in on this tab sees rendered before their first refetch.
    client.setQueryData(['transactions', 'list'], [{ id: 't-1', merchant: 'Corner Store' }])

    await user.click(await screen.findByRole('button', { name: /delete my account…/i }))
    const dialog = await screen.findByTestId('delete-account-dialog')
    await user.type(within(dialog).getByLabelText(/your password/i), 'correct-horse-battery')
    await user.click(within(dialog).getByRole('button', { name: /delete my account$/i }))

    // A DELETE that carries a body is legal but intermediaries drop it, which
    // would turn an irreversible decision into an unexplained 403.
    await waitFor(() => expect(mswState.lastAccountDeleteVerb).toBe('POST'))
    await waitFor(() => expect(useAuthStore.getState().status).toBe('anonymous'))
    expect(useAuthStore.getState().user).toBeNull()
    expect(await screen.findByText('LOGIN PAGE')).toBeInTheDocument()

    // Signing out is not just the store: `useLogout` clears the query cache for
    // this exact reason, and a deletion that skipped it would leave the deleted
    // account's ledger live in memory on a public route.
    expect(client.getQueryData(['transactions', 'list'])).toBeUndefined()
  })

  it('renders the last-admin 409 as an explanation with a way forward, not as an error', async () => {
    resetMswState({ lastAdminConflict: true })
    authenticate()
    const user = userEvent.setup()
    renderWithProviders(<DataSection />)

    await user.click(await screen.findByRole('button', { name: /delete my account…/i }))
    const dialog = await screen.findByTestId('delete-account-dialog')
    await user.type(within(dialog).getByLabelText(/your password/i), 'correct-horse-battery')
    await user.click(within(dialog).getByRole('button', { name: /delete my account$/i }))

    const message = await screen.findByTestId('delete-account-error')
    expect(message).toHaveAttribute('role', 'status')
    expect(message).toHaveTextContent(/only administrator/i)
    expect(useAuthStore.getState().status).toBe('authenticated')
  })

  it('shows a refused step-up as a refused credential', async () => {
    const user = userEvent.setup()
    renderWithProviders(<DataSection />)

    await user.click(await screen.findByRole('button', { name: /delete my account…/i }))
    const dialog = await screen.findByTestId('delete-account-dialog')
    await user.type(within(dialog).getByLabelText(/your password/i), 'not-my-password')
    await user.click(within(dialog).getByRole('button', { name: /delete my account$/i }))

    const message = await screen.findByTestId('delete-account-error')
    expect(message).toHaveAttribute('role', 'alert')
    expect(message).toHaveTextContent(/that password or code was not accepted/i)
    expect(useAuthStore.getState().status).toBe('authenticated')
  })
})
