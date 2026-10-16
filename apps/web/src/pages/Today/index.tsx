/**
 * Today — the one page that has always spoken a real contract, kept as the house
 * pattern for every rewrite in this wave.
 *
 * What changed in 20a: every amount renders through `MoneyDisplay` with the
 * currency the server sent alongside it, and every date through `useUserFormat`.
 * There is no `$` literal, no bare `toLocaleString`, and no hardcoded `en-CA` on
 * this page (defect I12, gap P2-15).
 *
 * What changed in 201: the page had been built against a `DashboardToday` shape
 * that predated `timezone` and `byCurrency`, so it could only ever show the
 * caller's own currency and could not say which day, in whose zone, the figures
 * described.
 *
 *  - **`timezone`.** The server computes "today" and "this month" from the
 *    member's own `users.timezone` (gap P2-15). The number is the server's; this
 *    page never re-derives a boundary from the browser clock. Where the zone is
 *    not the browser's, the page says which zone the figures are for, because a
 *    Vancouver member reading a Toronto boundary needs to know that.
 *  - **`byCurrency`.** One subtotal per currency the household actually holds,
 *    each labelled with its own code and rendered in its own currency. **No two
 *    of them are ever added together** — there is no exchange rate anywhere in
 *    this product (owner decision 2), so a household total across currencies is
 *    a number that cannot exist and is not shown.
 */
import { Link } from 'react-router-dom'
import { QlCard } from '@components/ui/QlCard/QlCard'
import { QlBadge } from '@components/ui/QlBadge/QlBadge'
import { QlProgress } from '@components/ui/QlProgress/QlProgress'
import { QlChart } from '@components/ui/QlChart/QlChart'
import { QlSkeleton } from '@components/ui/QlSkeleton/QlSkeleton'
import { ErrorBoundary } from '@components/ErrorBoundary/ErrorBoundary'
import { MoneyDisplay } from '@components/MoneyDisplay/MoneyDisplay'
import { EmptyState } from '@components/EmptyState/EmptyState'
import { SyncFreshness } from '@components/SyncFreshness/SyncFreshness'
import { useAccounts } from '@hooks/useAccounts'
import { useDashboardToday } from '@hooks/useDashboardToday'
import { useDailySpendingTrend } from '@hooks/useDailySpendingTrend'
import { useCurrentBudget } from '@hooks/useCurrentBudget'
import { useEnvelopes } from '@hooks/useEnvelopes'
import { useRecentTransactions } from '@hooks/useRecentTransactions'
import { useCategories } from '@hooks/useCategories'
import { useUserFormat } from '@hooks/useUserFormat'
import { directionOf, isDirectional, signedAmount } from '@api/transactionDirection'
import styles from './Today.module.css'

/** The zone the browser happens to be in — used ONLY to decide whether to name the server's. */
function browserTimezone(): string | null {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone || null
  } catch {
    return null
  }
}

function BalanceCard() {
  const today = useDashboardToday()
  // The line is drawn in ONE currency — the member's own reporting currency —
  // and its window ends on the server's day, not this device's. Both come from
  // the dashboard answer, so the query is idle until that answer is here.
  const trend = useDailySpendingTrend(7, today.data?.currency, today.data?.date)
  const format = useUserFormat()

  if (today.isPending) return <QlSkeleton data-testid="balance-skeleton" />
  if (today.isError) throw today.error

  const d = today.data
  const zoneDiffers = browserTimezone() !== null && browserTimezone() !== d.timezone

  return (
    <QlCard>
      <div data-testid="today-balance" className={styles.balanceCard}>
        <div className={styles.balanceEyebrow}>Net this month</div>
        <div className={styles.balanceAmount}>
          <MoneyDisplay
            amount={d.netThisMonth}
            currency={d.currency}
            data-testid="today-net"
          />
        </div>
        <div className={styles.balanceSubrow}>
          <span className={styles.balanceLabel}>Spent this month</span>
          <MoneyDisplay amount={d.spentThisMonth} currency={d.currency} colored={false} />
        </div>
        <p className={styles.zoneNote} data-testid="today-date">
          Figures for {format.formatDate(d.date)}
          {zoneDiffers ? `, computed in ${d.timezone} — your account's time zone, not this device's.` : '.'}
        </p>
        {trend.data && trend.data.length > 0 && (
          <>
            {/* Labelled with the currency it is in, because it is in exactly
                one: a line that added CAD to USD would need a rate this product
                does not have. */}
            <p className={styles.zoneNote} data-testid="today-trend-note">
              Daily spending over the last 7 days, in {d.currency} only.
            </p>
            <QlChart
              variant="spark"
              data={trend.data.map((p) => ({ value: p.total }))}
              color="var(--sage)"
              height={56}
            />
          </>
        )}
      </div>
    </QlCard>
  )
}

/**
 * One block per currency the household actually holds.
 *
 * Deliberately a list of separate figures with no sum beneath it: adding CAD to
 * USD would need an exchange rate, this product has none, and a plausible-looking
 * blended total is exactly the kind of number the trust rule forbids.
 */
function ByCurrencyCard() {
  const today = useDashboardToday()

  if (today.isPending) return <QlSkeleton data-testid="by-currency-skeleton" />
  if (today.isError) throw today.error

  const totals = today.data.byCurrency
  if (totals.length === 0) {
    return (
      <QlCard>
        <EmptyState title="No activity yet">
          <p>Once transactions land, each currency you hold gets its own totals here.</p>
        </EmptyState>
      </QlCard>
    )
  }

  return (
    <QlCard>
      <div data-testid="today-by-currency" className={styles.budgetsCard}>
        <div className={styles.sectionTitle}>This month, by currency</div>
        <p className={styles.zoneNote}>
          Each currency is reported on its own. Trove never converts between them, so there is no
          combined total.
        </p>
        <ul className={styles.currencyList}>
          {totals.map((c) => (
            <li
              key={c.currency}
              className={styles.currencyRow}
              data-testid={`today-currency-${c.currency}`}
            >
              <span className={styles.currencyCode}>{c.currency}</span>
              <span className={styles.currencyFigures}>
                <span className={styles.balanceLabel}>Net</span>
                <MoneyDisplay amount={c.netThisMonth} currency={c.currency} />
                <span className={styles.balanceLabel}>Spent</span>
                <MoneyDisplay amount={c.spentThisMonth} currency={c.currency} colored={false} />
                <span className={styles.balanceLabel}>Income</span>
                <MoneyDisplay amount={c.incomeThisMonth} currency={c.currency} colored={false} />
              </span>
            </li>
          ))}
        </ul>
      </div>
    </QlCard>
  )
}

function BudgetCard() {
  const q = useCurrentBudget()
  // `BudgetPeriodResponse` carries no `envelopes` field (§202: the old
  // hand-written shape embedded them, which does not match the server) — the
  // list is a separate, unpaged `GET /v1/budgets/{id}/envelopes`.
  const envelopes = useEnvelopes(q.data?.id ?? null)
  if (q.isPending) return <QlSkeleton data-testid="budgets-skeleton" />
  if (q.isError) throw q.error
  if (!q.data) {
    return (
      <QlCard>
        <EmptyState title="No budget for this month">
          <p>Budgets are built from what you have actually spent, so add some transactions first.</p>
        </EmptyState>
      </QlCard>
    )
  }
  if (envelopes.isPending) return <QlSkeleton data-testid="budgets-skeleton" />
  if (envelopes.isError) throw envelopes.error
  return (
    <QlCard>
      <div data-testid="today-budgets" className={styles.budgetsCard}>
        <div className={styles.sectionTitle}>Budget overview</div>
        <div className={styles.budgetList}>
          {envelopes.data.map((env) => (
            <div key={env.id} className={styles.budgetRow}>
              <div className={styles.budgetMeta}>
                <span className={styles.budgetName}>{env.categoryName}</span>
                <span className={styles.budgetAmt}>
                  <MoneyDisplay amount={env.spent} currency={env.currency} colored={false} />
                  <span className={styles.sep}>/</span>
                  <MoneyDisplay amount={env.allocated} currency={env.currency} colored={false} />
                </span>
              </div>
              {/* `health.healthPercentage`/`overPace` are the server's own figures
                  (§202) — never a client recomputation of spent/allocated. Red means
                  spent more than allocated (healthPercentage > 100), not "ahead of pace". */}
              <QlProgress
                value={env.health.healthPercentage}
                max={100}
                label={env.categoryName}
                height={8}
              />
            </div>
          ))}
        </div>
      </div>
    </QlCard>
  )
}

/**
 * How fresh each bank connection is (C-01r). Only SimpleFIN accounts are listed —
 * they are the ones the scheduled sync refreshes — and a household with none
 * sees no card at all.
 */
function BankSyncCard() {
  const accounts = useAccounts()
  const banks = (accounts.data ?? []).filter((a) => a.sourceKind === 'SIMPLEFIN')
  if (banks.length === 0) return null

  return (
    <QlCard>
      <div data-testid="today-bank-sync" className={styles.budgetsCard}>
        <div className={styles.txnHeader}>
          <span className={styles.sectionTitle}>Bank connections</span>
          <Link to="/accounts" className={styles.cta}>
            Accounts
          </Link>
        </div>
        <ul className={styles.currencyList}>
          {banks.map((a) => (
            <li key={a.id} className={styles.currencyRow}>
              <span className={styles.budgetName}>{a.displayName}</span>
              <span className={styles.txnDate}>
                {a.syncStatus === 'ERROR' ? 'Last sync failed · ' : ''}
                <SyncFreshness account={a} />
              </span>
            </li>
          ))}
        </ul>
      </div>
    </QlCard>
  )
}

function RecentTransactionsCard() {
  const q = useRecentTransactions(4)
  const categories = useCategories()
  const format = useUserFormat()

  if (q.isPending) return <QlSkeleton data-testid="txns-skeleton" />
  if (q.isError) throw q.error

  const txns = q.data ?? []
  if (txns.length === 0) {
    return (
      <QlCard padding="none">
        <EmptyState
          title="No transactions yet"
          actions={
            <Link to="/accounts" className={styles.cta}>
              Connect an account
            </Link>
          }
        >
          <p>Once an account is connected or a file is imported, activity shows up here.</p>
        </EmptyState>
      </QlCard>
    )
  }

  const categoryById = new Map((categories.data ?? []).map((c) => [c.id, c.name]))
  return (
    <QlCard padding="none" className={styles.txnCard}>
      <div data-testid="today-recent-txns" className={styles.txnSection}>
        <div className={styles.txnHeader}>
          <span className={styles.sectionTitle}>Recent transactions</span>
          <Link to="/transactions" className={styles.cta}>
            See all
          </Link>
        </div>
        {txns.map((t) => {
          const merchant = t.merchantName ?? t.description ?? '—'
          const categoryName = t.categoryId ? categoryById.get(t.categoryId) : null
          // The server sends a positive magnitude plus a type; the direction is
          // the contract's reading of that type. A REFUND is money coming BACK
          // (the server's own aggregates net it against spend), and a type the
          // contract gives no direction to is shown unsigned rather than guessed.
          const signed = signedAmount(t)
          const directional = isDirectional(t.type)
          return (
            <div key={t.id} className={styles.txnRow}>
              <div className={styles.txnLeft}>
                <span className={styles.txnMerchant}>{merchant}</span>
                {categoryName && (
                  <QlBadge variant={directionOf(t.type) === 'in' ? 'sage' : 'neutral'}>
                    {categoryName}
                  </QlBadge>
                )}
              </div>
              <div className={styles.txnRight}>
                <MoneyDisplay amount={signed} currency={t.currency} colored={directional} />
                <span className={styles.txnDate}>{format.formatDate(t.date)}</span>
              </div>
            </div>
          )
        })}
      </div>
    </QlCard>
  )
}

export default function Today() {
  return (
    <div className={styles.page}>
      <header className={styles.header}>
        <h1 data-testid="today-heading" className={styles.heading}>
          Today
        </h1>
      </header>

      <div data-testid="today-grid" className={styles.grid}>
        <ErrorBoundary label="balance">
          <BalanceCard />
        </ErrorBoundary>

        <ErrorBoundary label="currency totals">
          <ByCurrencyCard />
        </ErrorBoundary>

        <ErrorBoundary label="budgets">
          <BudgetCard />
        </ErrorBoundary>

        <ErrorBoundary label="bank connections">
          <BankSyncCard />
        </ErrorBoundary>

        <ErrorBoundary label="recent transactions">
          <RecentTransactionsCard />
        </ErrorBoundary>
      </div>
    </div>
  )
}
