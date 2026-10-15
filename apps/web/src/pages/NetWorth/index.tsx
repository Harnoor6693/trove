/**
 * Net worth — one series per currency (F2: never blended), assets and
 * liabilities as their own CRUD lists, and an explicit snapshot action
 * (§4.7, step 203).
 *
 * `GET /v1/networth/series` never writes; an empty `series` means no
 * snapshots exist in the requested range, not that none were taken. Each
 * currency's chart carries a text-alternative table right below it — a line
 * chart is not readable by a screen reader (§9 accessibility rule).
 */
import { useState } from 'react'
import { QlButton } from '@components/ui/QlButton/QlButton'
import { QlCard } from '@components/ui/QlCard/QlCard'
import { QlChart } from '@components/ui/QlChart/QlChart'
import { compactCurrency } from '@components/ui/QlChart/compactCurrency'
import { QlSkeleton } from '@components/ui/QlSkeleton/QlSkeleton'
import { EmptyState } from '@components/EmptyState/EmptyState'
import { MoneyDisplay } from '@components/MoneyDisplay/MoneyDisplay'
import { useUserFormat } from '@hooks/useUserFormat'
import { useNetWorthSeries, useTakeNetWorthSnapshotMutation } from '@hooks/useNetWorth'
import { netWorthErrorCopy } from './errorCopy'
import { AssetsPanel } from './AssetsPanel'
import { LiabilitiesPanel } from './LiabilitiesPanel'
import type { NetWorthRange, NetWorthSeries } from '@api/types'
import styles from './NetWorth.module.css'

const RANGES: NetWorthRange[] = ['3M', '6M', '1Y', 'ALL']
const RANGE_LABEL: Record<NetWorthRange, string> = {
  '3M': '3 months',
  '6M': '6 months',
  '1Y': '1 year',
  ALL: 'All time',
}

function CurrencySeriesBlock({ series }: { series: NetWorthSeries }) {
  const format = useUserFormat()
  const latest = series.points[series.points.length - 1]
  const chartData = series.points.map((p) => ({
    label: format.formatDate(p.snapshotDate),
    value: Number(p.netWorth),
  }))

  return (
    <div className={styles.currencyBlock} data-testid={`networth-series-${series.currency}`}>
      <h3 className={styles.currencyTitle}>{series.currency}</h3>
      {latest && (
        <div className={styles.currencyLatest}>
          <span>
            Net worth <MoneyDisplay amount={latest.netWorth} currency={series.currency} />
          </span>
          <span className={styles.hint}>as of {format.formatDate(latest.snapshotDate)}</span>
        </div>
      )}
      {series.points.length === 0 ? (
        <EmptyState title="No snapshots yet in this range">
          <p>Take a snapshot to start this currency's history.</p>
        </EmptyState>
      ) : (
        <>
          {series.points.length < 2 ? (
            <div data-testid="networth-single-point" className={styles.currencyLatest}>
              <span>
                Assets <MoneyDisplay amount={latest.totalAssets} currency={series.currency} colored={false} />
              </span>
              <span>
                Liabilities <MoneyDisplay amount={latest.totalLiabilities} currency={series.currency} colored={false} />
              </span>
              <span className={styles.hint}>
                The trend appears after your second snapshot. Take one now, or come back later.
              </span>
            </div>
          ) : (
            <QlChart
              variant="line"
              data={chartData}
              color="var(--sage)"
              height={220}
              valueFormat={compactCurrency(format.locale, series.currency)}
            />
          )}
          {/* Text alternative for the chart above — a line is not readable by a
              screen reader. Visually hidden, not display:none, so it still
              reaches assistive tech (§9 accessibility rule). */}
          <table className={styles.srOnly}>
            <caption>{series.currency} net worth by date</caption>
            <thead>
              <tr>
                <th scope="col">Date</th>
                <th scope="col">Total assets</th>
                <th scope="col">Total liabilities</th>
                <th scope="col">Net worth</th>
              </tr>
            </thead>
            <tbody>
              {series.points.map((p) => (
                <tr key={p.snapshotDate}>
                  <td>{format.formatDate(p.snapshotDate)}</td>
                  <td>
                    <MoneyDisplay amount={p.totalAssets} currency={series.currency} />
                  </td>
                  <td>
                    <MoneyDisplay amount={p.totalLiabilities} currency={series.currency} />
                  </td>
                  <td>
                    <MoneyDisplay amount={p.netWorth} currency={series.currency} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </>
      )}
    </div>
  )
}

export default function NetWorth() {
  const [range, setRange] = useState<NetWorthRange>('1Y')
  const series = useNetWorthSeries(range)
  const snapshot = useTakeNetWorthSnapshotMutation()

  return (
    <div className={styles.page}>
      <div className={styles.header}>
        <div>
          <h1 className={styles.heading}>Net worth</h1>
          <p className={styles.lede}>
            Everything you own minus everything you owe, from the snapshots you have taken. Each
            currency is shown on its own; Trove never converts between them.
          </p>
        </div>
        <div className={styles.headerActions}>
          <label className={styles.selectLabel} htmlFor="networth-range">
            Range
          </label>
          <select
            id="networth-range"
            className={styles.select}
            value={range}
            onChange={(e) => setRange(e.target.value as NetWorthRange)}
          >
            {RANGES.map((r) => (
              <option key={r} value={r}>
                {RANGE_LABEL[r]}
              </option>
            ))}
          </select>
          <QlButton variant="primary" loading={snapshot.isPending} onClick={() => snapshot.mutate()}>
            Take a snapshot now
          </QlButton>
        </div>
      </div>

      {snapshot.isError && (
        <p className={styles.error} role="alert">
          {netWorthErrorCopy(snapshot.error)}
        </p>
      )}
      {snapshot.isSuccess && (
        <p className={styles.status} role="status" data-testid="snapshot-taken">
          Snapshot recorded. The series below now reflects it.
        </p>
      )}

      <QlCard>
        {series.isPending && <QlSkeleton data-testid="networth-series-skeleton" />}
        {series.isError && (
          <p className={styles.error} role="alert">
            {netWorthErrorCopy(series.error)}
          </p>
        )}
        {series.data && series.data.series.length === 0 && (
          <EmptyState title="No net-worth history yet">
            <p>Take your first snapshot to start tracking it.</p>
          </EmptyState>
        )}
        {series.data && series.data.series.length > 0 && (
          <div className={styles.stack} data-testid="networth-series-list">
            {series.data.series.map((s) => (
              <CurrencySeriesBlock key={s.currency} series={s} />
            ))}
          </div>
        )}
      </QlCard>

      <AssetsPanel />
      <LiabilitiesPanel />
    </div>
  )
}
