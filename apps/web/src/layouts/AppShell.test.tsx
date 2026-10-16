import { describe, expect, it } from 'vitest'
import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithProviders } from '@/test/test-utils'
import { DEFAULT_ME, resetMswState } from '@/test/msw/handlers'
import { useAuthStore } from '@stores/authStore'
import { AppShell } from './AppShell'
import { BOTTOM_TABS, NAV_ITEMS, OVERFLOW_TABS } from './navItems'

function setViewport(width: number) {
  Object.defineProperty(window, 'innerWidth', { value: width, writable: true })
  window.dispatchEvent(new Event('resize'))
}

function authenticate() {
  useAuthStore.setState({ status: 'authenticated', user: { ...DEFAULT_ME }, hydrationError: null })
}

describe('AppShell navigation', () => {
  it('every nav destination is reachable on a phone (defect I5)', () => {
    // The old bottom bar was NAV_ITEMS.slice(0, 5) with no overflow, so five of
    // the ten destinations had no affordance at all on a phone.
    const reachable = new Set([...BOTTOM_TABS, ...OVERFLOW_TABS].map((i) => i.to))
    expect(reachable.size).toBe(NAV_ITEMS.length)
    for (const item of NAV_ITEMS) expect(reachable.has(item.to)).toBe(true)
  })

  it('lists no route that the router does not serve', async () => {
    const { routes } = await import('@/router')
    const shellChildren = routes.find((r) => r.path === '/')?.children ?? []
    const served = new Set(
      shellChildren.map((c) => ('index' in c && c.index ? '/' : `/${(c as { path: string }).path}`))
    )
    for (const item of NAV_ITEMS) expect(served.has(item.to)).toBe(true)
  })

  it('offers a skip link that targets the main landmark (D1-17)', () => {
    authenticate()
    resetMswState()
    renderWithProviders(<AppShell />)
    const skip = screen.getByRole('link', { name: 'Skip to content' })
    expect(skip).toHaveAttribute('href', '#main-content')
    expect(screen.getByRole('main')).toHaveAttribute('id', 'main-content')
  })

  it('exposes the overflow destinations behind More on a narrow viewport', async () => {
    authenticate()
    setViewport(500)
    const user = userEvent.setup()
    renderWithProviders(<AppShell />)

    expect(screen.queryByTestId('nav-more-sheet')).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /more/i }))
    const sheet = screen.getByTestId('nav-more-sheet')
    for (const item of OVERFLOW_TABS) {
      expect(sheet).toHaveTextContent(item.label)
    }
  })

  it('offers a sign-out control on every authenticated route (defect I2)', () => {
    authenticate()
    setViewport(1400)
    renderWithProviders(<AppShell />)
    expect(screen.getByRole('button', { name: /sign out/i })).toBeInTheDocument()
  })

  it('signs the user out through the store, not a page reload', async () => {
    authenticate()
    setViewport(1400)
    const user = userEvent.setup()
    renderWithProviders(<AppShell />)
    await user.click(screen.getByRole('button', { name: /sign out/i }))
    expect(useAuthStore.getState().status).toBe('anonymous')
    expect(useAuthStore.getState().user).toBeNull()
  })

  it("the bell's accessible name includes the live unread count, not just the word Notifications (step 203)", async () => {
    authenticate()
    setViewport(1400)
    resetMswState({
      notifications: [
        {
          id: 'notif-a',
          type: 'BUDGET_THRESHOLD',
          title: 'A',
          body: 'A',
          actionUrl: null,
          read: false,
          dismissed: false,
          createdAt: new Date().toISOString(),
          readAt: null,
          dismissedAt: null,
        },
        {
          id: 'notif-b',
          type: 'SYNC_FAILED',
          title: 'B',
          body: 'B',
          actionUrl: null,
          read: false,
          dismissed: false,
          createdAt: new Date().toISOString(),
          readAt: null,
          dismissedAt: null,
        },
      ],
    })
    renderWithProviders(<AppShell />)

    await waitFor(() =>
      expect(screen.getByTestId('notifications-bell')).toHaveAccessibleName('Notifications, 2 unread')
    )
    // The badge's own text is decorative — the count is real information and
    // lives in the link's accessible name, not only in a visual pill.
    expect(screen.getByTestId('notifications-bell')).toHaveTextContent('2')
  })

  it.each([
    ['desktop sidebar', 1400],
    ['phone bottom bar', 500],
  ])('links to the source code on the %s layout (AGPL-3.0 §13, step 28)', (_layout, width) => {
    authenticate()
    setViewport(width)
    renderWithProviders(<AppShell />)
    const link = screen.getByRole('link', { name: 'Source code' })
    expect(link).toHaveAttribute('href', 'https://github.com/Harnoor6693/trove')
    expect(link).toHaveAttribute('target', '_blank')
    expect(link.getAttribute('rel') ?? '').toContain('noopener')
    expect(screen.getByText(/free software under the AGPL-3\.0/i)).toBeInTheDocument()
  })

  it('the bell reads "no unread" rather than a bare zero when the inbox is clear', async () => {
    authenticate()
    setViewport(1400)
    resetMswState({ notifications: [] })
    renderWithProviders(<AppShell />)

    await waitFor(() =>
      expect(screen.getByTestId('notifications-bell')).toHaveAccessibleName(
        'Notifications, no unread'
      )
    )
  })
})
