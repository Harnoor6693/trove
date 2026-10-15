/**
 * The Today sparkline's data, tested where the arithmetic actually is.
 *
 * Two rules are asserted here because both were broken and neither is visible in
 * a rendered `<svg>`:
 *
 *  1. **One currency, never a blend.** The hook used to add `Number(t.amount)`
 *     for every DEBIT row the ledger returned, whatever currency it was in, and
 *     draw the result beside the per-currency card that exists precisely because
 *     two currencies are never summed. There is no exchange rate anywhere in
 *     this product (owner decision 2), so a blended line is a figure that cannot
 *     exist.
 *  2. **The window is the server's day.** It used to be `new Date()`,
 *     UTC-truncated — the browser clock deciding a boundary the server owns.
 */
import { describe, expect, it } from 'vitest'
import { screen } from '@testing-library/react'
import { renderWithProviders } from '@/test/test-utils'
import { DEFAULT_ME, mswState } from '@/test/msw/handlers'
import { useAuthStore } from '@stores/authStore'
import { useDailySpendingTrend } from './useDailySpendingTrend'
import type { CurrencyCode } from '@api/types'

function authenticate() {
  useAuthStore.setState({ status: 'authenticated', user: { ...DEFAULT_ME }, hydrationError: null })
}

/** A probe rather than a chart: the numbers are what is under test. */
function Probe({ currency, endDate }: { currency?: CurrencyCode; endDate?: string }) {
  const q = useDailySpendingTrend(7, currency, endDate)
  if (!q.data) return <p>no data</p>
  return (
    <ul data-testid="totals">
      {q.data.map((p) => (
        <li key={p.date}>{`${p.date}=${p.total}`}</li>
      ))}
    </ul>
  )
}

describe('useDailySpendingTrend', () => {
  it('adds up only the rows in the caller’s own currency', async () => {
    authenticate()
    const base = mswState.transactions[0]
    mswState.transactions = [
      { ...base, id: 'cad-1', date: '2026-09-04', amount: '10.00', currency: 'CAD', type: 'DEBIT' },
      // A USD row on the same day. Adding it in would need a rate that does not
      // exist; it must simply not be part of a CAD line.
      { ...base, id: 'usd-1', date: '2026-09-04', amount: '999.00', currency: 'USD', type: 'DEBIT' },
    ]
    renderWithProviders(<Probe currency="CAD" endDate="2026-09-06" />)

    const list = await screen.findByTestId('totals')
    expect(list.textContent).toContain('2026-09-04=10')
    expect(list.textContent).not.toContain('1009')
    expect(list.textContent).not.toContain('999')
  })

  it('takes its window from the server’s date, never from the browser clock', async () => {
    authenticate()
    renderWithProviders(<Probe currency="CAD" endDate="2026-08-20" />)
    await screen.findByTestId('totals')

    // Seven days ending on the server's own "today" for this member: the
    // boundary is the server's, and this client never recomputes one.
    expect(mswState.lastTransactionsQuery?.to).toBe('2026-08-20')
    expect(mswState.lastTransactionsQuery?.from).toBe('2026-08-14')
    expect(mswState.lastTransactionsQuery?.type).toBe('DEBIT')
  })

  it('asks for nothing until the dashboard has said which day and which currency', async () => {
    authenticate()
    renderWithProviders(<Probe />)
    expect(await screen.findByText('no data')).toBeInTheDocument()
    expect(mswState.lastTransactionsQuery).toBeNull()
  })
})
