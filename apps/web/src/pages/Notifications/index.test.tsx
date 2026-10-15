/**
 * Notifications inbox — behaviour, not "it rendered".
 *
 * `listNotifications` is paged (`meta`, not `rows.length`); mark-read and
 * mark-all-read must be reflected by `GET /v1/notifications/unread-count` —
 * the same route the shell's bell polls — not merely by the row's own
 * checkbox disappearing.
 */
import { describe, expect, it } from 'vitest'
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithProviders } from '@/test/test-utils'
import { DEFAULT_ME, mswState, resetMswState } from '@/test/msw/handlers'
import { useAuthStore } from '@stores/authStore'
import { getUnreadNotificationCount } from '@api/notifications'
import Notifications from './index'

function authenticate() {
  useAuthStore.setState({ status: 'authenticated', user: { ...DEFAULT_ME }, hydrationError: null })
}

describe('Notifications inbox', () => {
  it('pages using the SERVER’s meta, not rows.length', async () => {
    authenticate()
    resetMswState({
      notifications: Array.from({ length: 25 }, (_, i) => ({
        id: `notif-${i}`,
        type: 'BUDGET_THRESHOLD' as const,
        title: `Alert ${i}`,
        body: `Body ${i}`,
        actionUrl: null,
        read: false,
        dismissed: false,
        createdAt: new Date().toISOString(),
        readAt: null,
        dismissedAt: null,
      })),
    })
    const user = userEvent.setup()
    renderWithProviders(<Notifications />)

    const list = await screen.findByTestId('notifications-list')
    expect(within(list).getAllByTestId(/^notification-/)).toHaveLength(20)
    expect(screen.getByText(/Page 1 of 2/)).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Next' }))
    await waitFor(() =>
      expect(within(screen.getByTestId('notifications-list')).getAllByTestId(/^notification-/)).toHaveLength(5)
    )
  })

  it('marking one notification read decreases the unread count the shell polls', async () => {
    authenticate()
    resetMswState()
    const before = await getUnreadNotificationCount()
    const user = userEvent.setup()
    renderWithProviders(<Notifications />)

    const row = await screen.findByTestId('notification-notif-budget')
    await user.click(within(row).getByRole('button', { name: 'Mark read' }))

    await waitFor(async () => {
      const after = await getUnreadNotificationCount()
      expect(after.unread).toBe(before.unread - 1)
    })
  })

  it('mark-all-read clears the unread count to zero', async () => {
    authenticate()
    resetMswState()
    const user = userEvent.setup()
    renderWithProviders(<Notifications />)

    await screen.findByTestId('notifications-list')
    await user.click(screen.getByRole('button', { name: 'Mark all read' }))

    await waitFor(async () => {
      const after = await getUnreadNotificationCount()
      expect(after.unread).toBe(0)
    })
    expect(await screen.findByTestId('mark-all-done')).toBeInTheDocument()
  })

  it('dismissing a notification removes it from the inbox permanently, not merely marks it read', async () => {
    authenticate()
    resetMswState()
    const user = userEvent.setup()
    renderWithProviders(<Notifications />)

    const row = await screen.findByTestId('notification-notif-sync')
    await user.click(within(row).getByRole('button', { name: 'Dismiss' }))

    await waitFor(() =>
      expect(mswState.notifications.find((n) => n.id === 'notif-sync')?.dismissed).toBe(true)
    )
    await waitFor(() =>
      expect(screen.queryByTestId('notification-notif-sync')).not.toBeInTheDocument()
    )
  })

  it('derives its type copy from the closed NotificationTypeCode enum, including RECEIPT_PROCESSED', async () => {
    authenticate()
    resetMswState()
    renderWithProviders(<Notifications />)

    const row = await screen.findByTestId('notification-notif-receipt')
    expect(row).toHaveTextContent('Receipt needs a look')
  })

  it('keeps "Mark all read" enabled off the GLOBAL unread count, even when the CURRENT page has nothing unread', async () => {
    authenticate()
    // Page 1 (rows 0-19) is entirely read; the only unread row sits on page 2.
    // `markAllNotificationsRead` is a server-wide operation, so its button
    // must reflect the household's true unread state, not what page 1 shows.
    resetMswState({
      notifications: Array.from({ length: 21 }, (_, i) => ({
        id: `notif-${i}`,
        type: 'BUDGET_THRESHOLD' as const,
        title: `Alert ${i}`,
        body: `Body ${i}`,
        actionUrl: null,
        read: i < 20,
        dismissed: false,
        createdAt: new Date().toISOString(),
        readAt: i < 20 ? new Date().toISOString() : null,
        dismissedAt: null,
      })),
    })
    renderWithProviders(<Notifications />)

    await screen.findByTestId('notifications-list')
    expect(screen.getByText(/Page 1 of 2/)).toBeInTheDocument()
    await waitFor(() => expect(screen.getByRole('button', { name: 'Mark all read' })).toBeEnabled())
  })

  it("does not disable every OTHER row's buttons while one row's mark-read is in flight", async () => {
    authenticate()
    resetMswState()
    const user = userEvent.setup()
    renderWithProviders(<Notifications />)

    const budgetRow = await screen.findByTestId('notification-notif-budget')
    const syncRow = await screen.findByTestId('notification-notif-sync')

    await user.click(within(budgetRow).getByRole('button', { name: 'Mark read' }))
    // While the budget row's mutation is in flight, the sync row's own
    // "Mark read" button must stay clickable — a mutation-wide loading flag
    // would disable every row, even though only one row is actually mutating.
    expect(within(syncRow).getByRole('button', { name: 'Mark read' })).toBeEnabled()

    await waitFor(() =>
      expect(mswState.notifications.find((n) => n.id === 'notif-budget')?.read).toBe(true)
    )
  })
})
