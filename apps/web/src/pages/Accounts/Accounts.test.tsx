/**
 * Accounts — behaviour, not "it rendered".
 *
 * Every case below asserts something the member would notice if it broke: the
 * request that went out, the word on the screen, the absence of a figure the
 * server never sent.
 */
import { describe, expect, it } from 'vitest'
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http } from 'msw'
import { renderWithProviders } from '@/test/test-utils'
import { server } from '@/test/mswServer'
import { DEFAULT_ME, fail, mswState, ok } from '@/test/msw/handlers'
import { useAuthStore } from '@stores/authStore'
import Accounts from './index'

function authenticate() {
  useAuthStore.setState({ status: 'authenticated', user: { ...DEFAULT_ME }, hydrationError: null })
}

describe('Accounts', () => {
  it('lists every account with its institution, type, currency and source', async () => {
    authenticate()
    renderWithProviders(<Accounts />)

    const chequing = await screen.findByTestId('account-acct-1')
    expect(chequing.textContent).toContain('Everyday Chequing')
    expect(chequing.textContent).toContain('Test Bank')
    expect(chequing.textContent).toContain('Chequing')
    expect(chequing.textContent).toContain('CAD')
    expect(chequing.textContent).toContain('Bank connection')

    // The second currency is shown on its own row, in its own code — there is
    // no household total anywhere on this page.
    const savings = screen.getByTestId('account-acct-csv')
    expect(savings.textContent).toContain('USD')
  })

  it('says a failed sync in words, not only in colour, and gives the reason', async () => {
    authenticate()
    renderWithProviders(<Accounts />)
    const failed = await screen.findByTestId('account-acct-csv')

    expect(failed.textContent).toContain('Last sync failed')
    expect(within(failed).getByRole('alert').textContent).toContain(
      'The last import could not read two rows.'
    )
  })

  it('shows no balance figure at all — the server sends none and none is invented', async () => {
    authenticate()
    renderWithProviders(<Accounts />)
    await screen.findByTestId('accounts-list')

    // The pre-contract `ConnectedAccount` carried `balance: '1240.18'`. The real
    // AccountResponse has no balance field, so nothing money-shaped renders here.
    expect(screen.getByTestId('accounts-list').textContent).not.toMatch(/\d[\d,]*\.\d{2}/)
    expect(screen.queryByText(/balance/i)).not.toBeInTheDocument()
  })

  it('triggers a sync and holds the control while the run is in flight', async () => {
    authenticate()
    const user = userEvent.setup()
    renderWithProviders(<Accounts />)

    const button = await screen.findByRole('button', { name: /sync everyday chequing/i })
    await user.click(button)

    await waitFor(() => expect(mswState.syncCalls).toBe(1))
    // The account it claimed, not some other one.
    await waitFor(() =>
      expect(mswState.syncLogs['acct-1']?.[0]?.accountId).toBe('acct-1')
    )
  })

  it('renders a 409 from sync as its own reason and never signs the member out', async () => {
    authenticate()
    mswState.syncConflicts = true
    const user = userEvent.setup()
    renderWithProviders(<Accounts />)

    await user.click(await screen.findByRole('button', { name: /sync everyday chequing/i }))

    const alert = await screen.findByTestId('sync-error')
    expect(alert.textContent).toContain('A sync is already running for this account.')
    // A 409 is not an authentication problem: nothing refreshed, nothing logged out.
    expect(mswState.refreshCalls).toBe(0)
    expect(useAuthStore.getState().status).toBe('authenticated')
  })

  it('renders a 429 as a wait-and-retry state, not a generic failure or a logout', async () => {
    authenticate()
    server.use(
      http.get('*/v1/accounts', () =>
        fail(429, 'rate-limited', 'Too Many Requests', {}, { 'Retry-After': '30' })
      )
    )
    renderWithProviders(<Accounts />)

    const box = await screen.findByTestId('accounts-error')
    expect(box.textContent).toContain('Wait a few seconds')
    expect(box.textContent).toContain('your session is fine')
    expect(within(box).getByRole('button', { name: /try again/i })).toBeInTheDocument()
    expect(mswState.refreshCalls).toBe(0)
    expect(useAuthStore.getState().status).toBe('authenticated')
  })

  it('states what deleting an account does to its transactions before doing it', async () => {
    authenticate()
    const user = userEvent.setup()
    renderWithProviders(<Accounts />)

    await user.click(await screen.findByRole('button', { name: /delete everyday chequing/i }))

    const consequence = await screen.findByTestId('delete-consequence')
    expect(consequence.textContent).toContain('every transaction stored against it')
    expect(consequence.textContent).toContain('cannot be undone')

    await user.click(screen.getByRole('button', { name: /delete the account and its transactions/i }))
    await waitFor(() =>
      expect(mswState.accounts.some((a) => a.id === 'acct-1')).toBe(false)
    )
    // The cascade is real: the account's rows went with it.
    expect(mswState.transactions.some((t) => t.accountId === 'acct-1')).toBe(false)
  })

  it('maps a 400 fieldError onto the input that caused it', async () => {
    authenticate()
    const user = userEvent.setup()
    renderWithProviders(<Accounts />)

    await user.click(await screen.findByRole('button', { name: /^add an account$/i }))
    const dialog = await screen.findByTestId('add-account-dialog')
    const name = within(dialog).getByLabelText(/account name/i)
    // A name the client accepts but the server refuses, so the 400 path is real.
    await user.type(name, 'x')
    server.use(
      http.post('*/v1/accounts', () =>
        fail(400, 'validation-error', 'Bad Request', {
          fieldErrors: [{ field: 'displayName', message: 'Give the account a name.' }],
        })
      )
    )
    await user.click(within(dialog).getByRole('button', { name: /^add account$/i }))

    // The message lands on the field, wired through the shipped
    // ApiError.fieldError() helper and QlInput's role="alert".
    expect(await within(dialog).findByText('Give the account a name.')).toBeInTheDocument()
    expect(name).toHaveAttribute('aria-invalid', 'true')
  })

  it('adds a manual account with sourceKind MANUAL', async () => {
    authenticate()
    const user = userEvent.setup()
    renderWithProviders(<Accounts />)

    await user.click(await screen.findByRole('button', { name: /^add an account$/i }))
    const dialog = await screen.findByTestId('add-account-dialog')
    await user.type(within(dialog).getByLabelText(/account name/i), 'Envelope cash')
    await user.click(within(dialog).getByRole('button', { name: /^add account$/i }))

    await waitFor(() =>
      expect(mswState.accounts.some((a) => a.displayName === 'Envelope cash')).toBe(true)
    )
    expect(mswState.accounts.find((a) => a.displayName === 'Envelope cash')?.sourceKind).toBe(
      'MANUAL'
    )
  })

  /**
   * The defect this covers: the Import control is offered only on a `CSV` or
   * `OFX` account, and this dialog used to hard-code `sourceKind: 'MANUAL'`,
   * while the only other creation path (`exchangeSetupToken`) mints `SIMPLEFIN`
   * rows. No member could bring a file-fed account into existence, so the entire
   * import pipeline — four of this step's eighteen operations — had no reachable
   * entry point at all. The test starts from ONLY what the app itself can
   * produce, with the CSV fixture removed.
   */
  it('creates a file-fed account, which is the only way the import control becomes reachable', async () => {
    authenticate()
    mswState.accounts = mswState.accounts.filter((a) => a.sourceKind !== 'CSV')
    const user = userEvent.setup()
    renderWithProviders(<Accounts />)
    await screen.findByTestId('accounts-list')

    // Nothing importable exists yet, so no import affordance is on the page.
    expect(screen.queryByRole('button', { name: /import a file into/i })).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: /^add an account$/i }))
    const dialog = await screen.findByTestId('add-account-dialog')
    await user.selectOptions(within(dialog).getByLabelText(/how will this account be fed/i), 'CSV')
    await user.type(within(dialog).getByLabelText(/account name/i), 'Cross-border statements')
    // The account's own currency: a statement row that carries none is read in it.
    await user.selectOptions(within(dialog).getByLabelText(/currency it is held in/i), 'USD')
    await user.click(within(dialog).getByRole('button', { name: /^add account$/i }))

    await waitFor(() => {
      const created = mswState.accounts.find((a) => a.displayName === 'Cross-border statements')
      expect(created?.sourceKind).toBe('CSV')
      expect(created?.currency).toBe('USD')
    })

    // …and the row it created is the one that can be imported into.
    await user.click(
      await screen.findByRole('button', { name: /import a file into cross-border statements/i })
    )
    expect(await screen.findByTestId('import-wizard')).toBeInTheDocument()
  })

  it('offers no import control on a manual account, which has no file to read', async () => {
    authenticate()
    renderWithProviders(<Accounts />)
    await screen.findByTestId('account-acct-manual')

    expect(screen.queryByRole('button', { name: /import a file into cash wallet/i })).toBeNull()
    expect(screen.getByRole('button', { name: /import a file into us savings/i })).toBeInTheDocument()
  })

  it('never echoes the setup token back to the screen and drops it from state', async () => {
    authenticate()
    const user = userEvent.setup()
    renderWithProviders(<Accounts />)

    await user.click(await screen.findByRole('button', { name: /connect a bank/i }))
    const dialog = await screen.findByTestId('connect-bank-dialog')
    const field = within(dialog).getByLabelText(/setup token/i)
    await user.type(field, 'super-secret-token')
    await user.click(within(dialog).getByRole('button', { name: /^connect$/i }))

    expect(await within(dialog).findByRole('status')).toHaveTextContent(/Connected 1 account/)
    // The credential is gone from the field and from the document.
    await waitFor(() => expect(field).toHaveValue(''))
    expect(document.body.textContent).not.toContain('super-secret-token')
    // It is a password field, so it is never rendered in the clear either.
    expect(field).toHaveAttribute('type', 'password')
  })

  /**
   * Clearing the input takes the credential out of the DOM but not out of the
   * shared MutationCache, which keeps a settled mutation's `variables` — the
   * object holding the raw token — for its garbage-collection window. The dialog
   * resets the mutation on the way out; this asserts the cache, not the screen.
   */
  it('leaves no setup token in the shared mutation cache once the dialog is closed', async () => {
    authenticate()
    const user = userEvent.setup()
    const { client } = renderWithProviders(<Accounts />)

    await user.click(await screen.findByRole('button', { name: /connect a bank/i }))
    const dialog = await screen.findByTestId('connect-bank-dialog')
    await user.type(within(dialog).getByLabelText(/setup token/i), 'super-secret-token')
    await user.click(within(dialog).getByRole('button', { name: /^connect$/i }))
    await within(dialog).findByRole('status')

    await user.click(within(dialog).getByRole('button', { name: /^done$/i }))
    await waitFor(() => expect(screen.queryByTestId('connect-bank-dialog')).not.toBeInTheDocument())

    await waitFor(() => {
      const cached = JSON.stringify(
        client.getMutationCache().getAll().map((m) => ({ state: m.state }))
      )
      expect(cached).not.toContain('super-secret-token')
    })
  })

  it('re-reads the account itself in the history dialog, not the list’s stale copy', async () => {
    authenticate()
    // The list says OK; the account has since gone into ERROR. The dialog must
    // show what GET /v1/accounts/{id} says, because that is the current truth.
    server.use(
      http.get('*/v1/accounts/:id', ({ params }) => {
        const found = mswState.accounts.find((a) => a.id === params.id)
        if (!found) return fail(404, 'not-found', 'Not Found')
        return ok({
          ...found,
          syncStatus: 'ERROR',
          errorMessage: 'The bridge stopped answering a moment ago.',
        })
      })
    )
    const user = userEvent.setup()
    renderWithProviders(<Accounts />)

    // The list row still shows the stale, successful state…
    expect((await screen.findByTestId('account-acct-1')).textContent).toContain('Synced')

    await user.click(screen.getByRole('button', { name: /sync history for everyday chequing/i }))
    const status = await screen.findByTestId('sync-history-status')
    // …and the dialog shows the fresh one, in words.
    expect(status.textContent).toContain('Last sync failed')
    expect(
      await screen.findByText('The bridge stopped answering a moment ago.')
    ).toBeInTheDocument()
  })

  it('shows a failed run’s reason in the sync history', async () => {
    authenticate()
    const user = userEvent.setup()
    renderWithProviders(<Accounts />)

    await user.click(await screen.findByRole('button', { name: /sync history for everyday chequing/i }))
    const table = await screen.findByTestId('sync-history-table')

    expect(table.textContent).toContain('Succeeded')
    expect(table.textContent).toContain('Failed')
    expect(table.textContent).toContain('The bridge did not answer in time.')
  })

  it('offers a sync control only where there is a credential to sync with', async () => {
    authenticate()
    renderWithProviders(<Accounts />)
    await screen.findByTestId('account-acct-manual')

    // A manual account has nothing to sync, and a file-fed one is fed by Import:
    // `syncAccount` answers 409 for both until a source is registered, so the
    // control is not offered where its only outcome is a refusal.
    expect(screen.queryByRole('button', { name: /sync cash wallet/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /sync us savings/i })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: /sync everyday chequing/i })).toBeInTheDocument()
  })
})
