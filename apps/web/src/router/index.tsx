/**
 * The route table.
 *
 * Only routes that exist appear here. `/test-harness/:component` is gone — a
 * dev-only component gallery, outside `AuthGuard`, shipping unauthenticated in
 * the production bundle (defect I14). `/tax/ca` and `/tax/us` are gone with the
 * tax engine; one `/tax-export` replaces them.
 *
 * Defect I8 is closed: every branch has an `errorElement`, and there is a
 * `path: '*'` catch-all, so a mistyped URL renders a branded 404 instead of React
 * Router's raw developer overlay.
 */
import { lazy, Suspense } from 'react'
import { createBrowserRouter } from 'react-router-dom'
import { AuthGuard } from './AuthGuard'
import { RouteError } from './RouteError'
import { AppShell } from '@/layouts/AppShell'

const LoginPage = lazy(() => import('@pages/Login'))
const RegisterPage = lazy(() => import('@pages/Register'))
const InvitePage = lazy(() => import('@pages/Invite'))
const ResetPasswordPage = lazy(() => import('@pages/ResetPassword'))
const OnboardingPage = lazy(() => import('@pages/Onboarding'))
const TodayPage = lazy(() => import('@pages/Today'))
const TransactionsPage = lazy(() => import('@pages/Transactions'))
const AccountsPage = lazy(() => import('@pages/Accounts'))
const BudgetsPage = lazy(() => import('@pages/Budgets'))
const CategoriesPage = lazy(() => import('@pages/Budgets/Categories'))
const RulesPage = lazy(() => import('@pages/Budgets/Rules'))
const GoalsPage = lazy(() => import('@pages/Budgets/Goals'))
const NetWorthPage = lazy(() => import('@pages/NetWorth'))
const SubscriptionsPage = lazy(() => import('@pages/Subscriptions'))
const ReceiptsPage = lazy(() => import('@pages/Receipts'))
const CoachPage = lazy(() => import('@pages/Coach'))
const TaxExportPage = lazy(() => import('@pages/TaxExport'))
const NotificationsPage = lazy(() => import('@pages/Notifications'))
const SettingsPage = lazy(() => import('@pages/Settings'))
const NotFoundPage = lazy(() => import('@pages/NotFound'))

const withSuspense = (element: React.ReactNode) => (
  <Suspense fallback={<div role="status">Loading…</div>}>{element}</Suspense>
)

export const routes = [
  { path: '/login', element: withSuspense(<LoginPage />), errorElement: <RouteError /> },
  { path: '/register', element: withSuspense(<RegisterPage />), errorElement: <RouteError /> },
  { path: '/invite/:token', element: withSuspense(<InvitePage />), errorElement: <RouteError /> },
  /**
   * PUBLIC, and deliberately outside `AuthGuard`, beside `/login` and
   * `/invite/:token`: the server mints reset links of exactly this shape
   * (`<publicUrl>/reset-password/{token}`) and the whole point of following one
   * is that the member cannot sign in. Until step 205 the link landed on the
   * 404 page.
   */
  {
    path: '/reset-password/:token',
    element: withSuspense(<ResetPasswordPage />),
    errorElement: <RouteError />,
  },
  {
    path: '/onboarding',
    errorElement: <RouteError />,
    element: withSuspense(
      <AuthGuard>
        <OnboardingPage />
      </AuthGuard>
    ),
  },
  {
    path: '/',
    errorElement: <RouteError />,
    element: (
      <AuthGuard>
        <AppShell />
      </AuthGuard>
    ),
    children: [
      { index: true, element: withSuspense(<TodayPage />) },
      { path: 'transactions', element: withSuspense(<TransactionsPage />) },
      { path: 'accounts', element: withSuspense(<AccountsPage />) },
      { path: 'budgets', element: withSuspense(<BudgetsPage />), errorElement: <RouteError /> },
      {
        path: 'budgets/categories',
        element: withSuspense(<CategoriesPage />),
        errorElement: <RouteError />,
      },
      { path: 'budgets/rules', element: withSuspense(<RulesPage />), errorElement: <RouteError /> },
      { path: 'budgets/goals', element: withSuspense(<GoalsPage />), errorElement: <RouteError /> },
      { path: 'networth', element: withSuspense(<NetWorthPage />) },
      { path: 'subscriptions', element: withSuspense(<SubscriptionsPage />) },
      { path: 'receipts', element: withSuspense(<ReceiptsPage />) },
      { path: 'coach', element: withSuspense(<CoachPage />) },
      { path: 'tax-export', element: withSuspense(<TaxExportPage />) },
      { path: 'notifications', element: withSuspense(<NotificationsPage />) },
      { path: 'settings', element: withSuspense(<SettingsPage />) },
    ],
  },
  { path: '*', element: withSuspense(<NotFoundPage />), errorElement: <RouteError /> },
]

export const router = createBrowserRouter(routes)
