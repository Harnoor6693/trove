/**
 * Transactions — the two traps the contract sets, and the truncation this page
 * exists to close.
 *
 * Every assertion here is about a REQUEST the client made or a decision the
 * member would see, not about "something rendered".
 */
import { describe, expect, it } from 'vitest'
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http } from 'msw'
import { renderWithProviders } from '@/test/test-utils'
import { server } from '@/test/mswServer'
import { DEFAULT_ME, fail, mswState, ok } from '@/test/msw/handlers'
import { useAuthStore } from '@stores/authStore'
import Transactions from './index'

function authenticate() {
  useAuthStore.setState({ status: 'authenticated', user: { ...DEFAULT_ME }, hydrationError: null })
}

/**
 * More rows than one page holds, cloned from the contract-typed fixture rather
 * than hand-written, so a page boundary is real.
 */
function seedManyRows(count: number) {
  const base = mswState.transactions[0]
  mswState.transactions = Array.from({ length: count }, (_, i) => ({
    ...base,
    id: `txn-page-${String(i).padStart(3, '0')}`,
    merchantName: `Merchant ${i}`,
    // Descending dates, so row 0 is newest and lands on page one.
    date: `2026-0${i < 9 ? '9' : '8'}-${String(28 - (i % 28)).padStart(2, '0')}`,
  }))
}

describe('Transactions — the paged list', () => {
  it('renders one page and reaches page two with the right query parameters', async () => {
    authenticate()
    seedManyRows(30)
    const user = userEvent.setup()
    renderWithProviders(<Transactions />)

    await screen.findByTestId('transactions-table')
    // Page one asked for page 0 and got the server's own limit back in `meta`.
    await waitFor(() => expect(mswState.lastTransactionsQuery?.page).toBe('0'))
    expect(mswState.lastTransactionsQuery?.limit).toBe('25')
    expect(screen.getByTestId('transactions-table').textContent).toContain('of 30 transactions')
    expect(screen.getAllByRole('row')).toHaveLength(26) // 25 rows + the header

    await user.click(screen.getByRole('button', { name: /next page/i }))

    // The second page is a second REQUEST, not a client-side slice.
    await waitFor(() => expect(mswState.lastTransactionsQuery?.page).toBe('1'))
    expect(await screen.findByText(/Page 2 of 2/)).toBeInTheDocument()
    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(6)) // 5 rows + header
  })

  it('sends every filter the operation declares, in the query string', async () => {
    authenticate()
    const user = userEvent.setup()
    renderWithProviders(<Transactions />)
    await screen.findByTestId('transactions-table')

    await user.selectOptions(screen.getByLabelText(/^type$/i), 'CREDIT')
    await waitFor(() => expect(mswState.lastTransactionsQuery?.type).toBe('CREDIT'))

    await user.selectOptions(screen.getByLabelText(/^account$/i), 'acct-1')
    await waitFor(() => expect(mswState.lastTransactionsQuery?.accountId).toBe('acct-1'))

    await user.selectOptions(screen.getByLabelText(/^order$/i), 'amount,asc')
    await waitFor(() => expect(mswState.lastTransactionsQuery?.sort).toBe('amount,asc'))
  })

  it('returns to page one when a filter changes, so page four of a new filter cannot be shown', async () => {
    authenticate()
    seedManyRows(30)
    const user = userEvent.setup()
    renderWithProviders(<Transactions />)
    await screen.findByTestId('transactions-table')

    await user.click(screen.getByRole('button', { name: /next page/i }))
    await waitFor(() => expect(mswState.lastTransactionsQuery?.page).toBe('1'))

    await user.selectOptions(screen.getByLabelText(/^type$/i), 'DEBIT')
    await waitFor(() => expect(mswState.lastTransactionsQuery?.page).toBe('0'))
  })

  it('renders a 429 as a wait-and-retry state and does NOT trigger a refresh or a logout', async () => {
    authenticate()
    mswState.transactionsRateLimited = true
    renderWithProviders(<Transactions />)

    const box = await screen.findByTestId('transactions-error')
    expect(box.textContent).toContain('Wait a few seconds')
    expect(box.textContent).toContain('your session is fine')
    expect(within(box).getByRole('button', { name: /try again/i })).toBeInTheDocument()
    // A 429 is a rate limit, not an expired session.
    expect(mswState.refreshCalls).toBe(0)
    expect(useAuthStore.getState().status).toBe('authenticated')
  })

  it('renders each amount in its OWN currency, so two currencies do not look alike', async () => {
    authenticate()
    // Put a USD row next to the CAD ones. Defect I12 was a MoneyDisplay that
    // destructured its `currency` prop away and printed a literal symbol, so a
    // CAD household and a USD household rendered identically.
    mswState.transactions = mswState.transactions.map((t) =>
      t.id === 'txn-2' ? { ...t, currency: 'USD', accountId: 'acct-csv' } : t
    )
    renderWithProviders(<Transactions />)
    const table = await screen.findByTestId('transactions-table')

    const cad = table.querySelector('[data-currency="CAD"]')
    const usd = table.querySelector('[data-currency="USD"]')
    // Every amount carries the currency it was sent with…
    expect(cad).not.toBeNull()
    expect(usd).not.toBeNull()
    // …and the two render differently, which is the whole point: in en-CA,
    // 1800 USD is "US$1,800.00" and 42.10 CAD is "$42.10".
    expect(usd?.textContent).toContain('US')
    expect(cad?.textContent).not.toContain('US')
    // The server sends a positive magnitude plus a type; the sign is the client's.
    expect(cad?.textContent?.startsWith('-')).toBe(true)
    // No element on the page renders an amount without a currency behind it.
    expect(table.querySelectorAll('[data-currency=""]')).toHaveLength(0)
  })

  it('shows an honest empty state rather than an error when nothing matches', async () => {
    authenticate()
    mswState.transactions = []
    renderWithProviders(<Transactions />)
    expect(await screen.findByTestId('transactions-empty')).toBeInTheDocument()
    expect(screen.queryByTestId('transactions-error')).not.toBeInTheDocument()
  })
})

/**
 * The direction of a row is the contract's, not "CREDIT or else negative".
 * `TransactionType.java` says the sign lives on the type; `DashboardService`
 * nets a REFUND AGAINST spend, i.e. it is money coming back, and the spec's own
 * `TransactionType` description says the same. The old rule rendered a refunded
 * purchase as a further outflow on three separate screens.
 */
describe('Transactions — which way the money went', () => {
  it('shows a REFUND as money coming back, not as another outflow', async () => {
    authenticate()
    mswState.transactions = [
      { ...mswState.transactions[0], id: 'txn-refund', type: 'REFUND', amount: '42.10', merchantName: 'Returned jacket' },
    ]
    renderWithProviders(<Transactions />)
    const row = await screen.findByTestId('txn-row-txn-refund')

    const money = row.querySelector('[data-currency="CAD"]')
    expect(money?.textContent).toContain('42.10')
    expect(money?.textContent?.startsWith('-')).toBe(false)
  })

  it('shows a FEE as an outflow, like a debit', async () => {
    authenticate()
    mswState.transactions = [
      { ...mswState.transactions[0], id: 'txn-fee', type: 'FEE', amount: '3.50', merchantName: 'Monthly fee' },
    ]
    renderWithProviders(<Transactions />)
    const row = await screen.findByTestId('txn-row-txn-fee')
    expect(row.querySelector('[data-currency="CAD"]')?.textContent?.startsWith('-')).toBe(true)
  })

  it('refuses to guess a sign the contract does not state, and names the type instead', async () => {
    authenticate()
    // INTEREST is "earned or charged" and a TRANSFER leg is either side of a
    // movement: neither carries a direction, so neither gets an invented one.
    mswState.transactions = [
      { ...mswState.transactions[0], id: 'txn-interest', type: 'INTEREST', amount: '1.20', merchantName: 'Interest' },
    ]
    renderWithProviders(<Transactions />)
    const row = await screen.findByTestId('txn-row-txn-interest')

    const money = row.querySelector('[data-currency="CAD"]')
    expect(money?.textContent?.startsWith('-')).toBe(false)
    expect(row.textContent).toContain('Interest')
  })
})

describe('Transactions — the presence-meaningful PATCH', () => {
  it('sends ONLY the field that changed', async () => {
    authenticate()
    const user = userEvent.setup()
    renderWithProviders(<Transactions />)
    await screen.findByTestId('transactions-table')

    await user.click(screen.getByRole('button', { name: /details for corner market/i }))
    const detail = await screen.findByTestId('transaction-detail')

    await user.type(within(detail).getByLabelText(/^notes$/i), 'Reimbursable')
    await user.click(within(detail).getByRole('button', { name: /save changes/i }))

    await waitFor(() => expect(mswState.lastTransactionPatch).not.toBeNull())
    // The merchant was never touched, so the body does not mention it. A form
    // that spread its whole state would have carried merchantName, notes AND
    // tags, and the two absent ones would have been rewritten with their old
    // values — or, for a field the member had cleared elsewhere, wiped.
    expect(mswState.lastTransactionPatch).toEqual({ notes: 'Reimbursable' })
    expect(Object.keys(mswState.lastTransactionPatch ?? {})).toEqual(['notes'])
  })

  it('sends nothing at all when nothing changed', async () => {
    authenticate()
    const user = userEvent.setup()
    renderWithProviders(<Transactions />)
    await screen.findByTestId('transactions-table')

    await user.click(screen.getByRole('button', { name: /details for corner market/i }))
    const detail = await screen.findByTestId('transaction-detail')
    await user.click(within(detail).getByRole('button', { name: /save changes/i }))

    // An empty PATCH is a write that says nothing; it is not made.
    expect(mswState.lastTransactionPatch).toBeNull()
  })

  it('clears a field with an explicit null rather than by omitting it', async () => {
    authenticate()
    const user = userEvent.setup()
    renderWithProviders(<Transactions />)
    await screen.findByTestId('transactions-table')

    await user.click(screen.getByRole('button', { name: /details for corner market/i }))
    const detail = await screen.findByTestId('transaction-detail')
    await user.clear(within(detail).getByLabelText(/^merchant$/i))
    await user.click(within(detail).getByRole('button', { name: /save changes/i }))

    await waitFor(() => expect(mswState.lastTransactionPatch).not.toBeNull())
    // Emptying the box means "clear it" — which is `null`, not absence.
    expect(mswState.lastTransactionPatch).toEqual({ merchantName: null })
    await waitFor(() =>
      expect(mswState.transactions.find((t) => t.id === 'txn-1')?.merchantName).toBeNull()
    )
  })

  it('assigns a category inline with a one-key body', async () => {
    authenticate()
    const user = userEvent.setup()
    renderWithProviders(<Transactions />)
    await screen.findByTestId('transactions-table')

    const row = screen.getByTestId('txn-row-txn-2')
    await user.selectOptions(
      within(row).getByRole('combobox', { name: /category for employer inc/i }),
      'cat-transport'
    )

    await waitFor(() => expect(mswState.lastTransactionPatch).toEqual({ categoryId: 'cat-transport' }))
    await waitFor(() =>
      expect(mswState.transactions.find((t) => t.id === 'txn-2')?.categoryId).toBe('cat-transport')
    )
  })

  it('says so when the inline assignment is refused, instead of silently reverting', async () => {
    authenticate()
    server.use(
      http.patch('*/v1/transactions/:id', () =>
        fail(409, 'conflict', 'Conflict', {
          detail: 'Another change reached that transaction first.',
        })
      )
    )
    const user = userEvent.setup()
    renderWithProviders(<Transactions />)
    await screen.findByTestId('transactions-table')

    const row = screen.getByTestId('txn-row-txn-2')
    await user.selectOptions(
      within(row).getByRole('combobox', { name: /category for employer inc/i }),
      'cat-transport'
    )

    // The select snaps back on the next render; without this the member was told
    // nothing at all, having just watched their choice appear to take.
    const alert = await screen.findByTestId('inline-category-error')
    expect(alert).toHaveAttribute('role', 'alert')
    expect(alert.textContent).toContain('Another change reached that transaction first.')
    // A 409 is not an authentication problem.
    expect(mswState.refreshCalls).toBe(0)
    expect(useAuthStore.getState().status).toBe('authenticated')
  })
})

describe('Transactions — transfers are a patch, not a route', () => {
  it('links two legs by writing the same transferGroupId onto both', async () => {
    authenticate()
    const user = userEvent.setup()
    renderWithProviders(<Transactions />)
    await screen.findByTestId('transactions-table')

    await user.click(screen.getByRole('button', { name: /details for corner market/i }))
    const detail = await screen.findByTestId('transaction-detail')

    await user.selectOptions(within(detail).getByLabelText(/other side of it/i), 'txn-2')
    await user.click(within(detail).getByRole('button', { name: /mark these two as a transfer/i }))

    await waitFor(() => {
      const a = mswState.transactions.find((t) => t.id === 'txn-1')?.transferGroupId
      const b = mswState.transactions.find((t) => t.id === 'txn-2')?.transferGroupId
      expect(a).toBeTruthy()
      expect(a).toBe(b)
    })
  })

  /**
   * The other leg of a transfer is on a DIFFERENT account by definition, so a
   * picker filled from the list page's rows could not offer it while the list
   * was filtered by account — and the account filter is a supported, normal
   * thing to be using. The picker runs its own unfiltered query instead.
   */
  it('offers a leg from another account even while the list is filtered to one', async () => {
    authenticate()
    const user = userEvent.setup()
    renderWithProviders(<Transactions />)
    await screen.findByTestId('transactions-table')

    // Filter to the manual account: txn-2 (on acct-1) leaves the page entirely.
    await user.selectOptions(screen.getByLabelText(/^account$/i), 'acct-manual')
    await waitFor(() => expect(mswState.lastTransactionsQuery?.accountId).toBe('acct-manual'))
    expect(screen.queryByTestId('txn-row-txn-2')).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: /details for cash lunch/i }))
    const detail = await screen.findByTestId('transaction-detail')

    // …and it is still offered as the other side of a transfer.
    const picker = within(detail).getByLabelText(/other side of it/i)
    await waitFor(() =>
      expect(
        within(picker).getAllByRole('option').some((o) => o.textContent?.includes('Employer Inc'))
      ).toBe(true)
    )
    expect(within(detail).getByTestId('transfer-window-note').textContent).toContain('every account')
  })

  it('surfaces a refused FIRST leg and does not write the second', async () => {
    authenticate()
    // ONLY the first leg is refused; the partner's PATCH would succeed. That is
    // the case that used to vanish: two `mutate` calls share one observer, the
    // second detaches it from the first, and `mutate` swallows the rejection —
    // so the member saw no error at all while the partner was written anyway,
    // leaving one row marked as half a transfer.
    server.use(
      http.patch('*/v1/transactions/:id', ({ params }) => {
        if (params.id === 'txn-1') {
          return fail(409, 'conflict', 'Conflict', { detail: 'That transaction moved on already.' })
        }
        return ok(mswState.transactions.find((t) => t.id === params.id))
      })
    )
    const user = userEvent.setup()
    renderWithProviders(<Transactions />)
    await screen.findByTestId('transactions-table')

    await user.click(screen.getByRole('button', { name: /details for corner market/i }))
    const detail = await screen.findByTestId('transaction-detail')
    const picker = within(detail).getByLabelText(/other side of it/i)
    await waitFor(() =>
      expect(
        within(picker).getAllByRole('option').some((o) => o.textContent?.includes('Employer Inc'))
      ).toBe(true)
    )
    await user.selectOptions(picker, 'txn-2')
    await user.click(within(detail).getByRole('button', { name: /mark these two as a transfer/i }))

    expect(await within(detail).findByText(/That transaction moved on already\./)).toBeInTheDocument()
    // Neither leg is marked: the second write is never attempted.
    expect(mswState.transactions.find((t) => t.id === 'txn-1')?.transferGroupId).toBeNull()
    expect(mswState.transactions.find((t) => t.id === 'txn-2')?.transferGroupId).toBeNull()
  })

  it('unlinks a leg by sending transferGroupId: null explicitly', async () => {
    authenticate()
    mswState.transactions = mswState.transactions.map((t) =>
      t.id === 'txn-1' ? { ...t, transferGroupId: '00000000-0000-4000-8000-0000000000f1' } : t
    )
    const user = userEvent.setup()
    renderWithProviders(<Transactions />)
    await screen.findByTestId('transactions-table')

    await user.click(screen.getByRole('button', { name: /details for corner market/i }))
    const detail = await screen.findByTestId('transaction-detail')
    await user.click(within(detail).getByRole('button', { name: /this is not a transfer/i }))

    await waitFor(() => expect(mswState.lastTransactionPatch).toEqual({ transferGroupId: null }))
    // The explicit null is the whole request — an absent key would have left it linked.
    expect('transferGroupId' in (mswState.lastTransactionPatch ?? {})).toBe(true)
    await waitFor(() =>
      expect(mswState.transactions.find((t) => t.id === 'txn-1')?.transferGroupId).toBeNull()
    )
  })
})

describe('Transactions — manual entry', () => {
  it('offers only MANUAL accounts in the picker', async () => {
    authenticate()
    const user = userEvent.setup()
    renderWithProviders(<Transactions />)
    await screen.findByTestId('transactions-table')

    await user.click(screen.getByRole('button', { name: /record one by hand/i }))
    const dialog = await screen.findByTestId('manual-entry-dialog')
    const picker = within(dialog).getByLabelText(/^account$/i)

    const options = within(picker).getAllByRole('option').map((o) => o.textContent)
    expect(options.some((o) => o?.includes('Cash wallet'))).toBe(true)
    // The synced accounts would be a 409 from the server, so they are not offered.
    expect(options.some((o) => o?.includes('Everyday Chequing'))).toBe(false)
    expect(options.some((o) => o?.includes('US Savings'))).toBe(false)
  })

  it('explains the empty case instead of letting the server answer 409', async () => {
    authenticate()
    mswState.accounts = mswState.accounts.filter((a) => a.sourceKind !== 'MANUAL')
    const user = userEvent.setup()
    renderWithProviders(<Transactions />)
    await screen.findByTestId('transactions-table')

    await user.click(screen.getByRole('button', { name: /record one by hand/i }))
    expect(await screen.findByTestId('no-manual-accounts')).toHaveTextContent(
      /You need a manual account first/i
    )
    expect(screen.queryByRole('button', { name: /record it/i })).not.toBeInTheDocument()
  })

  it('records a transaction on the manual account, in that account’s currency', async () => {
    authenticate()
    const user = userEvent.setup()
    renderWithProviders(<Transactions />)
    await screen.findByTestId('transactions-table')

    await user.click(screen.getByRole('button', { name: /record one by hand/i }))
    const dialog = await screen.findByTestId('manual-entry-dialog')
    await user.type(within(dialog).getByLabelText(/^amount$/i), '12.34')
    await user.type(within(dialog).getByLabelText(/^date$/i), '2026-09-05')
    await user.type(within(dialog).getByLabelText(/^merchant/i), 'Farmers market')
    await user.click(within(dialog).getByRole('button', { name: /record it/i }))

    await waitFor(() => {
      const created = mswState.transactions.find((t) => t.merchantName === 'Farmers market')
      expect(created?.accountId).toBe('acct-manual')
      expect(created?.amount).toBe('12.34')
      expect(created?.manual).toBe(true)
      // The currency is the account's; the operation does not accept one.
      expect(created?.currency).toBe('CAD')
    })
  })
})

describe('Transactions — the detail view', () => {
  it('shows a not-found state for a 404 rather than crashing', async () => {
    authenticate()
    server.use(http.get('*/v1/transactions/:id', () => fail(404, 'not-found', 'Not Found')))
    const user = userEvent.setup()
    renderWithProviders(<Transactions />)
    await screen.findByTestId('transactions-table')

    await user.click(screen.getByRole('button', { name: /details for corner market/i }))

    const box = await screen.findByTestId('detail-error')
    expect(box.textContent).toContain('not in this household')
    expect(within(box).getByRole('button', { name: /back to the list/i })).toBeInTheDocument()
  })

  it('offers delete only on a hand-entered row', async () => {
    authenticate()
    const user = userEvent.setup()
    renderWithProviders(<Transactions />)
    await screen.findByTestId('transactions-table')

    await user.click(screen.getByRole('button', { name: /details for corner market/i }))
    let detail = await screen.findByTestId('transaction-detail')
    // A synced row would reappear on the next sync, so the control is absent.
    expect(within(detail).queryByRole('button', { name: /delete this transaction/i })).toBeNull()
    await user.click(within(detail).getByRole('button', { name: /^close$/i }))

    await user.click(screen.getByRole('button', { name: /details for cash lunch/i }))
    detail = await screen.findByTestId('transaction-detail')
    const remove = within(detail).getByRole('button', { name: /delete this transaction/i })
    await user.click(remove)

    // One click is not a delete: the row is unrecoverable, so it confirms and
    // says what is lost first, the way the account delete does.
    const confirm = await screen.findByTestId('confirm-delete-transaction')
    expect(confirm.textContent).toContain('permanent')
    expect(confirm.textContent).toContain('entered this row by hand')
    expect(mswState.transactions.some((t) => t.id === 'txn-manual')).toBe(true)

    await user.click(within(confirm).getByRole('button', { name: /delete it permanently/i }))
    await waitFor(() =>
      expect(mswState.transactions.some((t) => t.id === 'txn-manual')).toBe(false)
    )
  })

  it('lets the member back out of the delete confirmation', async () => {
    authenticate()
    const user = userEvent.setup()
    renderWithProviders(<Transactions />)
    await screen.findByTestId('transactions-table')

    await user.click(screen.getByRole('button', { name: /details for cash lunch/i }))
    const detail = await screen.findByTestId('transaction-detail')
    await user.click(within(detail).getByRole('button', { name: /delete this transaction/i }))
    await user.click(await screen.findByRole('button', { name: /keep it/i }))

    expect(screen.queryByTestId('confirm-delete-transaction')).not.toBeInTheDocument()
    expect(mswState.transactions.some((t) => t.id === 'txn-manual')).toBe(true)
  })
})
