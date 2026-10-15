/**
 * The authenticated shell: navigation, a sign-out control, and the routed page.
 *
 * Two defects are closed here.
 *
 *  - **I5** — the mobile bottom bar was `NAV_ITEMS.slice(0, 5)` with no overflow,
 *    so on a phone Receipts, Net Worth, Accounts, Tax and Settings were
 *    unreachable through any affordance at all. There is now a "More" sheet, and
 *    a compile-time check that every nav item is reachable from one of the two.
 *  - **I2** — there was no sign-out control anywhere in the app. There is one on
 *    every authenticated route now, in the top bar, alongside Settings' own.
 *
 * Only routes that exist and are either live or honestly labelled appear here.
 * Tax CA and Tax US are gone: they computed an estimated refund and RRSP room,
 * which is exactly the tax-engine output the owner decision cuts.
 *
 * The bell (§4.9, step 203) links to `/notifications` and polls
 * `getUnreadNotificationCount` (no socket, ever — owner decision). Its
 * accessible name includes the count itself, not just the word
 * "Notifications" — a badge that is `aria-hidden` with no other cue would
 * leave a screen-reader user unable to tell whether anything is unread.
 */
import { useState } from 'react'
import { NavLink, Outlet } from 'react-router-dom'
import { QlSidebar } from '@components/ui/QlSidebar/QlSidebar'
import { QlRail } from '@components/ui/QlRail/QlRail'
import { QlButton } from '@components/ui/QlButton/QlButton'
import { useWindowWidth } from '@hooks/useWindowWidth'
import { useLogout } from '@hooks/useLogout'
import { useUnreadCount } from '@hooks/useNotifications'
import { useAuthStore } from '@stores/authStore'
import styles from './AppShell.module.css'
import { BOTTOM_TABS, NAV_ITEMS, OVERFLOW_TABS } from './navItems'

/** AGPL-3.0 §13: every network user is offered the source. Login imports it too. */
export const SOURCE_CODE_URL = 'https://github.com/Harnoor6693/trove'

export function AppShell() {
  const width = useWindowWidth()
  const logout = useLogout()
  const user = useAuthStore((s) => s.user)
  const unread = useUnreadCount()
  const [moreOpen, setMoreOpen] = useState(false)

  const isDesktop = width >= 1200
  const isTablet = width >= 760 && width < 1200
  const isMobile = width < 760
  const unreadCount = unread.data?.unread ?? 0

  return (
    <div className={styles.shell}>
      {isDesktop && <QlSidebar items={NAV_ITEMS} />}
      {isTablet && <QlRail items={NAV_ITEMS} />}

      <main className={styles.main}>
        <div className={styles.topBar}>
          {user && <span className={styles.who}>{user.displayName || user.email}</span>}
          <NavLink
            to="/notifications"
            className={styles.bell}
            data-testid="notifications-bell"
            aria-label={unreadCount > 0 ? `Notifications, ${unreadCount} unread` : 'Notifications, no unread'}
          >
            <span aria-hidden="true">🔔</span>
            {unreadCount > 0 && (
              <span className={styles.bellBadge} aria-hidden="true">
                {unreadCount > 99 ? '99+' : unreadCount}
              </span>
            )}
          </NavLink>
          <QlButton variant="ghost" size="sm" onClick={() => void logout()}>
            Sign out
          </QlButton>
        </div>
        <Outlet />
        <footer className={styles.footer}>
          Trove is free software under the AGPL-3.0.{' '}
          <a href={SOURCE_CODE_URL} target="_blank" rel="noopener noreferrer">
            Source code
          </a>
        </footer>
      </main>

      {isMobile && (
        <>
          {moreOpen && (
            <div className={styles.moreSheet} data-testid="nav-more-sheet">
              {OVERFLOW_TABS.map((item) => (
                <NavLink
                  key={item.to}
                  to={item.to}
                  onClick={() => setMoreOpen(false)}
                  className={({ isActive }) =>
                    `${styles.moreItem} ${isActive ? styles.active : ''}`
                  }
                >
                  <span aria-hidden="true">{item.icon}</span>
                  <span>{item.label}</span>
                </NavLink>
              ))}
            </div>
          )}
          <nav className={styles.bottomBar} aria-label="Main navigation">
            {BOTTOM_TABS.map((item) => (
              <NavLink
                key={item.to}
                to={item.to}
                end={item.to === '/'}
                className={({ isActive }) =>
                  `${styles.bottomItem} ${isActive ? styles.active : ''}`
                }
              >
                <span aria-hidden="true">{item.icon}</span>
                <span>{item.label}</span>
              </NavLink>
            ))}
            <button
              type="button"
              className={styles.moreToggle}
              aria-expanded={moreOpen}
              onClick={() => setMoreOpen((open) => !open)}
            >
              <span aria-hidden="true">⋯</span>
              <span>More</span>
            </button>
          </nav>
        </>
      )}
    </div>
  )
}
