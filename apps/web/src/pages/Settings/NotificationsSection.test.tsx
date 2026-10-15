/**
 * Notification preferences — behaviour, not "it rendered".
 *
 * There is no push field anywhere in this form — that is a settled owner
 * decision (2026-09-03), not an omission, and it is asserted here so a future
 * regression is a failing test rather than a silent re-addition.
 */
import { describe, expect, it } from 'vitest'
import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithProviders } from '@/test/test-utils'
import { DEFAULT_ME, mswState, resetMswState } from '@/test/msw/handlers'
import { useAuthStore } from '@stores/authStore'
import { NotificationsSection } from './NotificationsSection'

function authenticate() {
  useAuthStore.setState({ status: 'authenticated', user: { ...DEFAULT_ME }, hydrationError: null })
}

describe('Notification preferences', () => {
  it('has no push notification control of any kind', async () => {
    authenticate()
    resetMswState()
    renderWithProviders(<NotificationsSection />)

    await screen.findByRole('heading', { name: 'Notifications' })
    expect(screen.queryByLabelText(/push/i)).not.toBeInTheDocument()
    expect(screen.queryByText(/device token/i)).not.toBeInTheDocument()
  })

  it('saves the whole form, with a blank email address sent as an explicit null', async () => {
    authenticate()
    resetMswState({
      notificationPreferences: {
        ...mswState.notificationPreferences,
        emailEnabled: true,
        emailAddress: 'alerts@example.test',
      },
    })
    const user = userEvent.setup()
    renderWithProviders(<NotificationsSection />)

    const emailField = await screen.findByLabelText('Email address')
    await user.clear(emailField)
    await user.click(screen.getByRole('button', { name: 'Save notification preferences' }))

    await waitFor(() =>
      expect(mswState.lastNotificationPreferencesBody?.emailAddress).toBeNull()
    )
    expect(await screen.findByTestId('notification-prefs-saved')).toBeInTheDocument()
  })

  it('changing the budget alert threshold sends the new whole-percent value', async () => {
    authenticate()
    resetMswState()
    const user = userEvent.setup()
    renderWithProviders(<NotificationsSection />)

    const field = await screen.findByLabelText('Budget alert threshold (%)')
    await user.clear(field)
    await user.type(field, '75')
    await user.click(screen.getByRole('button', { name: 'Save notification preferences' }))

    await waitFor(() =>
      expect(mswState.lastNotificationPreferencesBody?.budgetAlertThresholdPercent).toBe(75)
    )
  })

  it('refuses a non-numeric budget threshold and sends nothing (B4-05)', async () => {
    authenticate()
    resetMswState()
    const user = userEvent.setup()
    renderWithProviders(<NotificationsSection />)

    const field = await screen.findByLabelText('Budget alert threshold (%)')
    await user.clear(field)
    await user.type(field, 'abc')
    await user.click(screen.getByRole('button', { name: 'Save notification preferences' }))

    expect(await screen.findByText('Enter a whole number.')).toBeInTheDocument()
    expect(mswState.lastNotificationPreferencesBody).toBeNull()
    expect(screen.queryByTestId('notification-prefs-saved')).not.toBeInTheDocument()
  })
})
