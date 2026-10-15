/**
 * Subscriptions — the active list, per-currency summary, price-change alerts
 * and a manual scan trigger (§4.8, step 203).
 *
 * **`cancelSubscription` is a soft delete.** It stops Trove tracking the
 * merchant; it does NOT cancel anything with the merchant, and the
 * confirmation says so in plain words, because a member who believes
 * otherwise keeps paying.
 */
import { useState } from 'react'
import { QlBadge } from '@components/ui/QlBadge/QlBadge'
import { QlButton } from '@components/ui/QlButton/QlButton'
import { QlCard } from '@components/ui/QlCard/QlCard'
import { QlModal } from '@components/ui/QlModal/QlModal'
import { QlSkeleton } from '@components/ui/QlSkeleton/QlSkeleton'
import { QlTable, type QlTableColumn } from '@components/ui/QlTable/QlTable'
import { EmptyState } from '@components/EmptyState/EmptyState'
import { MoneyDisplay } from '@components/MoneyDisplay/MoneyDisplay'
import { useDialogFocus } from '@hooks/useDialogFocus'
import { useUserFormat } from '@hooks/useUserFormat'
import {
  useCancelSubscriptionMutation,
  useMarkSubscriptionAlertReadMutation,
  useScanSubscriptionsMutation,
  useSubscriptionAlerts,
  useSubscriptionSummary,
  useSubscriptions,
} from '@hooks/useSubscriptions'
import { subscriptionsErrorCopy } from './errorCopy'
import type { SubscriptionResponse, SubscriptionSignal } from '@api/types'
import styles from './Subscriptions.module.css'

const SIGNAL_BADGE: Record<SubscriptionSignal, { variant: 'sage' | 'amber' | 'red'; label: string }> = {
  GREEN: { variant: 'sage', label: 'On track' },
  AMBER: { variant: 'amber', label: 'Price changed' },
  RED: { variant: 'red', label: 'Charge missing' },
}

function SummaryPanel() {
  const summary = useSubscriptionSummary()
  const format = useUserFormat()

  return (
    <QlCard>
      <h2 className={styles.sectionTitle}>Monthly run-rate</h2>
      {summary.isPending && <QlSkeleton data-testid="summary-skeleton" />}
      {summary.isError && (
        <p className={styles.error} role="alert">
          {subscriptionsErrorCopy(summary.error)}
        </p>
      )}
      {summary.data && (
        <div className={styles.stack} data-testid="subscription-summary">
          <p className={styles.hint}>
            {summary.data.activeCount} active, {summary.data.flaggedCount} asking for a look.
          </p>
          {summary.data.byCurrency.length === 0 ? (
            <EmptyState title="Nothing detected yet">
              <p>Run a scan to detect your recurring charges.</p>
            </EmptyState>
          ) : (
            summary.data.byCurrency.map((c) => (
              <div key={c.currency} className={styles.currencyRow} data-testid={`summary-currency-${c.currency}`}>
                <div>
                  <strong>
                    <MoneyDisplay amount={c.monthlyRunRate} currency={c.currency} colored={false} />
                  </strong>{' '}
                  / month
                </div>
                <span className={styles.currencyMeta}>
                  {c.activeCount} active · {c.flaggedCount} flagged
                </span>
              </div>
            ))
          )}
          {summary.data.next14Days.length > 0 && (
            <div className={styles.stack} data-testid="upcoming-charges">
              <h3 className={styles.sectionTitle}>Next 14 days</h3>
              {summary.data.next14Days.map((day) => (
                <div key={day.date} className={styles.currencyRow}>
                  <span>{format.formatDate(day.date)}</span>
                  <span className={styles.currencyMeta}>
                    {day.totals.map((t, i) => (
                      <span key={t.currency}>
                        {i > 0 && ', '}
                        <MoneyDisplay amount={t.amount} currency={t.currency} colored={false} />
                      </span>
                    ))}
                  </span>
                </div>
              ))}
            </div>
          )}
        </div>
      )}
    </QlCard>
  )
}

function AlertsPanel() {
  const alerts = useSubscriptionAlerts()
  const markRead = useMarkSubscriptionAlertReadMutation()

  const rows = alerts.data ?? []
  if (alerts.isPending || (alerts.data && rows.length === 0)) {
    return (
      <QlCard>
        <h2 className={styles.sectionTitle}>Alerts</h2>
        {alerts.isPending && <QlSkeleton data-testid="alerts-skeleton" />}
        {alerts.data && rows.length === 0 && <p className={styles.hint}>No unread alerts.</p>}
      </QlCard>
    )
  }

  return (
    <QlCard>
      <h2 className={styles.sectionTitle}>Alerts</h2>
      {alerts.isError && (
        <p className={styles.error} role="alert">
          {subscriptionsErrorCopy(alerts.error)}
        </p>
      )}
      <ul className={styles.list} data-testid="subscription-alerts">
        {rows.map((alert) => (
          <li key={alert.id} className={styles.alertRow}>
            <p className={styles.alertMessage}>{alert.alertMessage}</p>
            <QlButton
              size="sm"
              variant="ghost"
              loading={markRead.isPending && markRead.variables === alert.id}
              onClick={() => markRead.mutate(alert.id)}
            >
              Acknowledge
            </QlButton>
          </li>
        ))}
      </ul>
      {markRead.isError && (
        <p className={styles.error} role="alert">
          {subscriptionsErrorCopy(markRead.error)}
        </p>
      )}
    </QlCard>
  )
}

function CancelDialog({
  subscription,
  onClose,
}: {
  subscription: SubscriptionResponse
  onClose: () => void
}) {
  const cancel = useCancelSubscriptionMutation()
  const ref = useDialogFocus<HTMLDivElement>(true)
  return (
    <QlModal isOpen onClose={onClose} title={`Stop tracking ${subscription.merchantName}?`}>
      <div ref={ref} tabIndex={-1} className={styles.stack} data-testid="cancel-subscription-dialog">
        <p className={styles.hint}>
          This only stops Trove tracking {subscription.merchantName} as a subscription. Trove does
          not contact the merchant, and it does not cancel anything with them — if you are still
          being charged, you will need to cancel it there yourself.
        </p>
        {cancel.isError && (
          <p className={styles.error} role="alert">
            {subscriptionsErrorCopy(cancel.error)}
          </p>
        )}
        <div className={styles.dialogActions}>
          <QlButton variant="ghost" onClick={onClose}>
            Keep tracking it
          </QlButton>
          <QlButton
            variant="danger"
            loading={cancel.isPending}
            onClick={() => cancel.mutate(subscription.id, { onSuccess: onClose })}
          >
            Stop tracking
          </QlButton>
        </div>
      </div>
    </QlModal>
  )
}

function SubscriptionsList() {
  const subscriptions = useSubscriptions()
  const format = useUserFormat()
  const [toCancel, setToCancel] = useState<SubscriptionResponse | null>(null)
  const rows = subscriptions.data ?? []

  const columns: QlTableColumn<SubscriptionResponse & Record<string, unknown>>[] = [
    { key: 'merchantName', header: 'Merchant' },
    {
      key: 'normalizedAmount',
      header: 'Amount',
      align: 'right',
      render: (row) => (
        <MoneyDisplay amount={row.normalizedAmount} currency={row.currency} colored={false} />
      ),
    },
    { key: 'billingCycle', header: 'Cycle' },
    {
      key: 'nextChargeDate',
      header: 'Next charge',
      render: (row) => (row.nextChargeDate ? format.formatDate(row.nextChargeDate) : '—'),
    },
    {
      key: 'signal',
      header: 'Status',
      render: (row) => (
        <QlBadge variant={SIGNAL_BADGE[row.signal].variant}>{SIGNAL_BADGE[row.signal].label}</QlBadge>
      ),
    },
    {
      key: 'actions',
      header: 'Actions',
      render: (row) => (
        <div className={styles.rowActions}>
          <QlButton size="sm" variant="ghost" onClick={() => setToCancel(row)}>
            Stop tracking
          </QlButton>
        </div>
      ),
    },
  ]

  return (
    <QlCard>
      <h2 className={styles.sectionTitle}>Detected subscriptions</h2>
      {subscriptions.isPending && <QlSkeleton data-testid="subscriptions-skeleton" />}
      {subscriptions.isError && (
        <p className={styles.error} role="alert">
          {subscriptionsErrorCopy(subscriptions.error)}
        </p>
      )}
      {subscriptions.data && rows.length === 0 && (
        <EmptyState title="Nothing detected yet">
          <p>Run a scan to detect recurring charges in your transaction history.</p>
        </EmptyState>
      )}
      {subscriptions.data && rows.length > 0 && (
        <div className={styles.tableWrap} data-testid="subscriptions-table">
          <QlTable
            columns={columns}
            rows={rows as (SubscriptionResponse & Record<string, unknown>)[]}
          />
        </div>
      )}
      {toCancel && <CancelDialog subscription={toCancel} onClose={() => setToCancel(null)} />}
    </QlCard>
  )
}

export default function Subscriptions() {
  const scan = useScanSubscriptionsMutation()

  return (
    <div className={styles.page}>
      <div className={styles.header}>
        <div>
          <h1 className={styles.heading}>Subscriptions</h1>
          <p className={styles.lede}>
            Detection runs on the server against your real transaction history. Recurring charges
            in more than one currency are never blended into one figure.
          </p>
        </div>
        <div className={styles.headerActions}>
          <QlButton variant="primary" loading={scan.isPending} onClick={() => scan.mutate()}>
            Scan now
          </QlButton>
        </div>
      </div>

      {scan.isError && (
        <p className={styles.error} role="alert">
          {subscriptionsErrorCopy(scan.error)}
        </p>
      )}
      {scan.isSuccess && (
        <p className={styles.status} role="status" data-testid="scan-done">
          Scan complete.
        </p>
      )}

      <SummaryPanel />
      <AlertsPanel />
      <SubscriptionsList />
    </div>
  )
}
