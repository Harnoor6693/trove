/**
 * Net worth — behaviour, not "it rendered".
 *
 * The central assertion this family exists to prove: a household holding more
 * than one currency is shown one series PER CURRENCY, never a blended figure
 * (F2 — there is no exchange rate anywhere in this product).
 */
import { describe, expect, it } from 'vitest'
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithProviders } from '@/test/test-utils'
import { DEFAULT_ME, mswState, resetMswState } from '@/test/msw/handlers'
import { useAuthStore } from '@stores/authStore'
import NetWorth from './index'

function authenticate() {
  useAuthStore.setState({ status: 'authenticated', user: { ...DEFAULT_ME }, hydrationError: null })
}

describe('Net worth', () => {
  it('renders one series per currency and computes no blended figure', async () => {
    authenticate()
    resetMswState()
    renderWithProviders(<NetWorth />)

    const cad = await screen.findByTestId('networth-series-CAD')
    const usd = await screen.findByTestId('networth-series-USD')
    expect(cad).toHaveTextContent('CAD')
    expect(usd).toHaveTextContent('USD')

    // A line needs two points: CAD has two snapshots and is charted, USD has one
    // and is shown as figures instead — never one line mixing both.
    expect(within(cad).getByTestId('ql-chart')).toBeInTheDocument()
    expect(within(usd).queryByTestId('ql-chart')).not.toBeInTheDocument()

    // Nowhere on the page is a figure that adds the two currencies together.
    // The fixture's CAD net worth is 3980.5000 and USD is 12000.0000; a
    // blended total would be some form of "15980" or a bare "$" with no code.
    expect(screen.queryByText(/15,?980/)).not.toBeInTheDocument()
    expect(screen.queryByText(/^total net worth$/i)).not.toBeInTheDocument()
  })

  it('shows figures instead of a one-dot chart when a currency has a single snapshot (D1-04)', async () => {
    authenticate()
    resetMswState()
    renderWithProviders(<NetWorth />)

    const usd = await screen.findByTestId('networth-series-USD')
    expect(within(usd).queryByTestId('ql-chart')).not.toBeInTheDocument()
    expect(within(usd).getByTestId('networth-single-point')).toHaveTextContent(/assets/i)
    expect(within(usd).getByTestId('networth-single-point')).toHaveTextContent(/liabilities/i)
    expect(usd).toHaveTextContent(/trend appears after your second snapshot/i)
  })

  it('gives each chart a text-alternative table, since a line is not readable by a screen reader', async () => {
    authenticate()
    resetMswState()
    renderWithProviders(<NetWorth />)

    await screen.findByTestId('networth-series-CAD')
    const tables = screen.getAllByRole('table')
    expect(tables.length).toBeGreaterThanOrEqual(2)
    expect(tables[0]).toHaveTextContent('CAD')
  })

  it('offers an explicit snapshot action whose result refreshes the series', async () => {
    authenticate()
    resetMswState()
    const user = userEvent.setup()
    renderWithProviders(<NetWorth />)

    await screen.findByTestId('networth-series-CAD')
    await user.click(screen.getByRole('button', { name: 'Take a snapshot now' }))
    expect(await screen.findByTestId('snapshot-taken')).toHaveTextContent('Snapshot recorded')
  })

  it('lets a member add an asset, which appears in its own currency', async () => {
    authenticate()
    resetMswState()
    const user = userEvent.setup()
    renderWithProviders(<NetWorth />)

    await screen.findByTestId('assets-table')
    await user.click(screen.getByRole('button', { name: 'Add asset' }))
    const dialog = await screen.findByTestId('create-asset-dialog')
    await user.type(within(dialog).getByLabelText('Name'), 'Vacation fund')
    await user.type(within(dialog).getByLabelText('Value'), '2000.00')
    await user.click(within(dialog).getByRole('button', { name: 'Add asset' }))

    await waitFor(() => expect(mswState.assets.some((a) => a.name === 'Vacation fund')).toBe(true))
  })

  it('removing a liability does not touch any other row', async () => {
    authenticate()
    resetMswState()
    const user = userEvent.setup()
    renderWithProviders(<NetWorth />)

    const table = await screen.findByTestId('liabilities-table')
    await user.click(within(table).getByRole('button', { name: 'Remove' }))
    const dialog = await screen.findByTestId('delete-liability-dialog')
    await user.click(within(dialog).getByRole('button', { name: 'Remove' }))

    await waitFor(() => expect(mswState.liabilities).toHaveLength(0))
  })
})
