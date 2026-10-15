/**
 * Lazy page components for the route table. Kept apart from `index.tsx` because
 * eslint-plugin-react-refresh (0.5+) flags `lazy()` components that sit in a file
 * which also exports non-components (the `routes` array).
 */
import { lazy } from 'react'

export const LoginPage = lazy(() => import('@pages/Login'))
export const RegisterPage = lazy(() => import('@pages/Register'))
export const InvitePage = lazy(() => import('@pages/Invite'))
export const ResetPasswordPage = lazy(() => import('@pages/ResetPassword'))
export const OnboardingPage = lazy(() => import('@pages/Onboarding'))
export const TodayPage = lazy(() => import('@pages/Today'))
export const TransactionsPage = lazy(() => import('@pages/Transactions'))
export const AccountsPage = lazy(() => import('@pages/Accounts'))
export const BudgetsPage = lazy(() => import('@pages/Budgets'))
export const CategoriesPage = lazy(() => import('@pages/Budgets/Categories'))
export const RulesPage = lazy(() => import('@pages/Budgets/Rules'))
export const GoalsPage = lazy(() => import('@pages/Budgets/Goals'))
export const NetWorthPage = lazy(() => import('@pages/NetWorth'))
export const SubscriptionsPage = lazy(() => import('@pages/Subscriptions'))
export const ReceiptsPage = lazy(() => import('@pages/Receipts'))
export const CoachPage = lazy(() => import('@pages/Coach'))
export const TaxExportPage = lazy(() => import('@pages/TaxExport'))
export const NotificationsPage = lazy(() => import('@pages/Notifications'))
export const SettingsPage = lazy(() => import('@pages/Settings'))
export const NotFoundPage = lazy(() => import('@pages/NotFound'))
