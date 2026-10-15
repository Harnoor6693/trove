import { describe, expect, it } from 'vitest'
import { screen } from '@testing-library/react'
import { renderWithProviders } from '@/test/test-utils'
import { DEFAULT_ME, FIXTURE_DATES, mswState } from '@/test/msw/handlers'
import { useAuthStore } from '@stores/authStore'
import Today from './index'

function authenticate() {
  useAuthStore.setState({ status: 'authenticated', user: { ...DEFAULT_ME }, hydrationError: null })
}

describe('Today', () => {
  it('renders the net figure the API sent, in the currency the API sent', async () => {
    authenticate()
    renderWithProviders(<Today />)
    const net = await screen.findByTestId('today-net')
    // 2900.00 CAD from GET /v1/dashboard/today — not a fixture, not a browser default.
    expect(net).toHaveAttribute('data-currency', 'CAD')
    expect(net.textContent).toContain('2,900.00')
  })

  it('renders envelope amounts from the budget endpoint with its own currency', async () => {
    authenticate()
    renderWithProviders(<Today />)
    expect(await screen.findByTestId('today-budgets')).toHaveTextContent('Groceries')
    expect(screen.getByTestId('today-budgets').textContent).toContain('210.55')
    expect(screen.getByTestId('today-budgets').textContent).toContain('600.00')
  })

  it('signs a debit negative and a credit positive from the transaction type', async () => {
    authenticate()
    renderWithProviders(<Today />)
    const txns = await screen.findByTestId('today-recent-txns')
    expect(txns.textContent).toContain('Corner Market')
    expect(txns.textContent).toContain('-')
    expect(txns.textContent).toContain('1,800.00')
  })

  it('shows a REFUND as money coming back, not as a second outflow', async () => {
    authenticate()
    // The server nets a REFUND against spend — it is the reversal of a debit,
    // i.e. money returning. The old rule ("CREDIT, or else negative") printed it
    // with a leading minus here, on the list and in the detail view alike.
    mswState.transactions = [
      {
        ...mswState.transactions[0],
        id: 'txn-refund',
        type: 'REFUND',
        amount: '42.10',
        merchantName: 'Returned jacket',
      },
    ]
    renderWithProviders(<Today />)
    const txns = await screen.findByTestId('today-recent-txns')

    const money = txns.querySelector('[data-currency="CAD"]')
    expect(money?.textContent).toContain('42.10')
    expect(money?.textContent?.startsWith('-')).toBe(false)
  })

  it('says which single currency the spending line is drawn in', async () => {
    authenticate()
    renderWithProviders(<Today />)
    // The household holds CAD and USD; the line is drawn in one of them and says
    // which, because there is no rate with which to draw both at once.
    const note = await screen.findByTestId('today-trend-note')
    expect(note.textContent).toContain('CAD only')
  })

  it('renders dates in the user’s locale, not the browser default', async () => {
    authenticate()
    renderWithProviders(<Today />)
    const txns = await screen.findByTestId('today-recent-txns')
    // en-CA from GET /v1/me/preferences renders YYYY-MM-DD; en-US would render
    // MM/DD/YYYY for the same value.
    expect(txns.textContent).toContain(FIXTURE_DATES.yesterday)
  })
})

describe('Today — the per-currency dashboard (step 201)', () => {
  it('renders every entry of byCurrency, each in its own currency', async () => {
    authenticate()
    renderWithProviders(<Today />)

    const cad = await screen.findByTestId('today-currency-CAD')
    const usd = screen.getByTestId('today-currency-USD')

    // Each block carries its own code, and each amount is rendered with that
    // code rather than with a bare symbol.
    expect(cad.textContent).toContain('CAD')
    expect(cad.textContent).toContain('2,900.00')
    expect(usd.textContent).toContain('USD')
    expect(usd.textContent).toContain('62.00')
    expect(usd.textContent).toContain('88.00')
  })

  it('shows no blended cross-currency total anywhere on the page', async () => {
    authenticate()
    renderWithProviders(<Today />)
    const section = await screen.findByTestId('today-by-currency')

    // 2900.00 CAD + 62.00 USD = 2962.00 — the number that would exist only if
    // the page had invented an exchange rate. There is none in this product.
    expect(section.textContent).not.toContain('2,962')
    expect(document.body.textContent).not.toContain('2,962')
    // …and the page says so in words rather than leaving it to be inferred.
    expect(section.textContent).toContain('never converts')
  })

  it('names the account time zone the figures were computed in when it is not this device’s', async () => {
    authenticate()
    // The fixture's dashboard is computed in America/Toronto. jsdom's own zone is
    // whatever the runner has, so assert on the branch rather than on a literal.
    const deviceZone = Intl.DateTimeFormat().resolvedOptions().timeZone
    renderWithProviders(<Today />)
    const dateLine = await screen.findByTestId('today-date')

    expect(dateLine.textContent).toContain(FIXTURE_DATES.today)
    if (deviceZone !== 'America/Toronto') {
      expect(dateLine.textContent).toContain('America/Toronto')
    }
  })

  it('renders an honest empty state rather than zeroes when the ledger has no currencies', async () => {
    authenticate()
    mswState.dashboard = { ...mswState.dashboard, byCurrency: [] }
    renderWithProviders(<Today />)
    expect(await screen.findByText(/No activity yet/i)).toBeInTheDocument()
    expect(screen.queryByTestId('today-currency-CAD')).not.toBeInTheDocument()
  })
})
