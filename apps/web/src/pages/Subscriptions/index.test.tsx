/**
 * Subscriptions — behaviour, not "it rendered".
 */
import { describe, expect, it } from 'vitest'
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithProviders } from '@/test/test-utils'
import { DEFAULT_ME, mswState, resetMswState } from '@/test/msw/handlers'
import { useAuthStore } from '@stores/authStore'
import Subscriptions from './index'

function authenticate() {
  useAuthStore.setState({ status: 'authenticated', user: { ...DEFAULT_ME }, hydrationError: null })
}

describe('Subscriptions', () => {
  it('renders each currency of the summary separately, with no blended total', async () => {
    authenticate()
    resetMswState()
    renderWithProviders(<Subscriptions />)

    const summary = await screen.findByTestId('subscription-summary')
    const cadRow = within(summary).getByTestId('summary-currency-CAD')
    const usdRow = within(summary).getByTestId('summary-currency-USD')
    expect(within(cadRow).getByText((_, el) => el?.getAttribute('data-currency') === 'CAD')).toBeInTheDocument()
    expect(within(usdRow).getByText((_, el) => el?.getAttribute('data-currency') === 'USD')).toBeInTheDocument()
    // The two currencies' monthly run-rates are never added into one figure.
    expect(screen.queryByText(/^total$/i)).not.toBeInTheDocument()
  })

  it('the cancel confirmation says Trove stops tracking and the merchant is not contacted', async () => {
    authenticate()
    resetMswState()
    const user = userEvent.setup()
    renderWithProviders(<Subscriptions />)

    const table = await screen.findByTestId('subscriptions-table')
    await user.click(within(table).getAllByRole('button', { name: 'Stop tracking' })[0])

    const dialog = await screen.findByTestId('cancel-subscription-dialog')
    expect(dialog).toHaveTextContent('does not contact the merchant')
    expect(dialog).toHaveTextContent('does not cancel anything with them')

    await user.click(within(dialog).getByRole('button', { name: 'Stop tracking' }))
    await waitFor(() =>
      expect(mswState.subscriptions.find((s) => s.id === 'sub-spotify')?.cancelled).toBe(true)
    )
  })

  it('a cancelled subscription leaves the active list but is not contacted or hard-deleted', async () => {
    authenticate()
    resetMswState()
    renderWithProviders(<Subscriptions />)

    await screen.findByTestId('subscriptions-table')
    expect(screen.queryByText('CITY GYM')).not.toBeInTheDocument() // already cancelled in the fixture
    // The row still exists in state — a soft delete, never removed.
    expect(mswState.subscriptions.some((s) => s.merchantName === 'CITY GYM')).toBe(true)
  })

  it('runs a manual scan with an in-flight state', async () => {
    authenticate()
    resetMswState()
    const user = userEvent.setup()
    renderWithProviders(<Subscriptions />)

    await screen.findByTestId('subscriptions-table')
    await user.click(screen.getByRole('button', { name: 'Scan now' }))
    await waitFor(() => expect(mswState.scanCalls).toBe(1))
    expect(await screen.findByTestId('scan-done')).toHaveTextContent('Scan complete')
  })

  it('acknowledging an alert removes it from the unread list', async () => {
    authenticate()
    resetMswState()
    const user = userEvent.setup()
    renderWithProviders(<Subscriptions />)

    const alerts = await screen.findByTestId('subscription-alerts')
    expect(alerts).toHaveTextContent('SPOTIFY went up')
    await user.click(within(alerts).getByRole('button', { name: 'Acknowledge' }))

    await waitFor(() =>
      expect(mswState.subscriptionAlerts.find((a) => a.id === 'sub-alert-spotify')?.isRead).toBe(
        true
      )
    )
  })

  it("does not disable every OTHER alert's Acknowledge button while one is in flight", async () => {
    authenticate()
    resetMswState({
      subscriptionAlerts: [
        {
          id: 'sub-alert-spotify',
          householdId: 'household-1',
          createdBy: 'member-1',
          subscriptionId: 'sub-spotify',
          alertType: 'PRICE_INCREASE',
          alertMessage: 'SPOTIFY went up from 9.99 CAD to 11.99 CAD.',
          isRead: false,
          createdAt: new Date().toISOString(),
        },
        {
          id: 'sub-alert-netflix',
          householdId: 'household-1',
          createdBy: 'member-1',
          subscriptionId: 'sub-netflix',
          alertType: 'PRICE_INCREASE',
          alertMessage: 'NETFLIX went up from 15.99 CAD to 17.99 CAD.',
          isRead: false,
          createdAt: new Date().toISOString(),
        },
      ],
    })
    const user = userEvent.setup()
    renderWithProviders(<Subscriptions />)

    const alerts = await screen.findByTestId('subscription-alerts')
    const buttons = within(alerts).getAllByRole('button', { name: 'Acknowledge' })
    expect(buttons).toHaveLength(2)

    await user.click(buttons[0])
    // The second alert's own Acknowledge button must stay clickable while
    // only the first alert's mutation is in flight.
    expect(buttons[1]).toBeEnabled()

    await waitFor(() =>
      expect(mswState.subscriptionAlerts.find((a) => a.id === 'sub-alert-spotify')?.isRead).toBe(
        true
      )
    )
  })
})
