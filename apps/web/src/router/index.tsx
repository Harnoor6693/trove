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
import { Suspense } from 'react'
import { createBrowserRouter } from 'react-router-dom'
import { AuthGuard } from './AuthGuard'
import { RouteError } from './RouteError'
import { AppShell } from '@/layouts/AppShell'
import {
  LoginPage,
  RegisterPage,
  InvitePage,
  ResetPasswordPage,
  OnboardingPage,
  TodayPage,
  TransactionsPage,
  AccountsPage,
  BudgetsPage,
  CategoriesPage,
  RulesPage,
  GoalsPage,
  NetWorthPage,
  SubscriptionsPage,
  ReceiptsPage,
  CoachPage,
  TaxExportPage,
  NotificationsPage,
  SettingsPage,
  NotFoundPage,
} from './pages'

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
