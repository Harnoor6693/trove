/**
 * The sub-navigation for the four screens this family builds.
 *
 * Placement decision:
 * the nav already carries ten items and Budgets is already one of them, so
 * categories, merchant rules and goals are sub-routes of `/budgets` rather than
 * a new nav entry — an in-page tab strip, not a second destination.
 *
 * Plain links (not buttons), so every tab is independently reachable, bookmarkable
 * and reload-safe — a real navigation, not a client-only view switch.
 */
import { NavLink } from 'react-router-dom'
import styles from './Budgets.module.css'

const TABS = [
  { to: '/budgets', label: 'Overview', end: true },
  { to: '/budgets/categories', label: 'Categories', end: false },
  { to: '/budgets/rules', label: 'Merchant rules', end: false },
  { to: '/budgets/goals', label: 'Goals', end: false },
]

export function BudgetsTabs() {
  return (
    <nav className={styles.tabs} aria-label="Budgets sections">
      {TABS.map((tab) => (
        <NavLink
          key={tab.to}
          to={tab.to}
          end={tab.end}
          className={({ isActive }) => `${styles.tab} ${isActive ? styles.tabActive : ''}`}
        >
          {tab.label}
        </NavLink>
      ))}
    </nav>
  )
}
