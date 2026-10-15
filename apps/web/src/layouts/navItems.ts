/**
 * The navigation table, kept out of `AppShell.tsx` so the shell file exports only
 * a component (Fast Refresh) and so the nav can be asserted on directly.
 *
 * Only routes that exist are here. Defect I5: the phone bottom bar used to be
 * `NAV_ITEMS.slice(0, 5)` with no overflow, which made five of the ten
 * destinations unreachable on a phone by any affordance at all. The split below
 * is exhaustive by construction — every item is in exactly one of the two lists.
 */
import type { NavItem } from '@components/ui/QlSidebar/QlSidebar'

export const NAV_ITEMS: NavItem[] = [
  { to: '/', label: 'Today', icon: '📊' },
  { to: '/transactions', label: 'Transactions', icon: '💳' },
  { to: '/accounts', label: 'Accounts', icon: '🏦' },
  { to: '/budgets', label: 'Budgets', icon: '🎯' },
  { to: '/networth', label: 'Net Worth', icon: '📈' },
  { to: '/subscriptions', label: 'Subscriptions', icon: '🔄' },
  { to: '/receipts', label: 'Receipts', icon: '🧾' },
  { to: '/coach', label: 'Coach', icon: '🤖' },
  { to: '/tax-export', label: 'Tax Export', icon: '📄' },
  { to: '/notifications', label: 'Notifications', icon: '🔔' },
  { to: '/settings', label: 'Settings', icon: '⚙️' },
]

/** The four that fit a phone's bottom bar; everything else lives behind "More". */
export const BOTTOM_TABS = NAV_ITEMS.slice(0, 4)
export const OVERFLOW_TABS = NAV_ITEMS.slice(4)
