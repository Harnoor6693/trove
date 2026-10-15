/**
 * Notifications inbox (§4.9, step 203). Paged, undismissed only, newest
 * first. Mark-read and mark-all-read keep the shell's unread badge (§4.9,
 * `AppShell`) in step by sharing the `['notifications']` query prefix.
 */
import { useState } from 'react'
import { QlButton } from '@components/ui/QlButton/QlButton'
import { QlCard } from '@components/ui/QlCard/QlCard'
import { QlSkeleton } from '@components/ui/QlSkeleton/QlSkeleton'
import { EmptyState } from '@components/EmptyState/EmptyState'
import { useUserFormat } from '@hooks/useUserFormat'
import {
  useDismissNotificationMutation,
  useMarkAllNotificationsReadMutation,
  useMarkNotificationReadMutation,
  useNotifications,
  useUnreadCount,
} from '@hooks/useNotifications'
import { NOTIFICATION_TYPE_LABEL, notificationsErrorCopy } from './errorCopy'
import styles from './Notifications.module.css'

const LIMIT = 20

export default function Notifications() {
  const [page, setPage] = useState(0)
  const notifications = useNotifications({ page, limit: LIMIT })
  const unreadCount = useUnreadCount()
  const markRead = useMarkNotificationReadMutation()
  const markAllRead = useMarkAllNotificationsReadMutation()
  const dismiss = useDismissNotificationMutation()
  const format = useUserFormat()

  const rows = notifications.data?.rows ?? []
  const meta = notifications.data?.meta
  // `markAllNotificationsRead` is a GLOBAL operation (POST /v1/notifications/read-all
  // marks every unread row read across the whole inbox), so its availability must be
  // gated off the true household-wide unread count (the same `useUnreadCount` the
  // shell's bell polls) — NOT off whether the current page happens to contain an
  // unread row. A page with nothing unread on it must not hide the only affordance
  // for unread rows sitting on a later page.
  const hasGlobalUnread = (unreadCount.data?.unread ?? 0) > 0

  return (
    <div className={styles.page}>
      <div className={styles.header}>
        <div>
          <h1 className={styles.heading}>Notifications</h1>
          <p className={styles.lede}>What Trove has told you, kept whether or not you have read it yet.</p>
        </div>
        <div className={styles.headerActions}>
          <QlButton
            variant="ghost"
            loading={markAllRead.isPending}
            disabled={!hasGlobalUnread}
            onClick={() => markAllRead.mutate()}
          >
            Mark all read
          </QlButton>
        </div>
      </div>

      {markAllRead.isError && (
        <p className={styles.error} role="alert">
          {notificationsErrorCopy(markAllRead.error)}
        </p>
      )}
      {markAllRead.isSuccess && (
        <p className={styles.status} role="status" data-testid="mark-all-done">
          Marked {markAllRead.data.marked} notification{markAllRead.data.marked === 1 ? '' : 's'}{' '}
          read.
        </p>
      )}

      <QlCard>
        {notifications.isPending && <QlSkeleton data-testid="notifications-skeleton" />}
        {notifications.isError && (
          <p className={styles.error} role="alert">
            {notificationsErrorCopy(notifications.error)}
          </p>
        )}
        {notifications.data && rows.length === 0 && (
          <EmptyState title="Nothing here">
            <p>Notifications about your budgets, subscriptions and account syncs will appear here.</p>
          </EmptyState>
        )}
        {notifications.data && rows.length > 0 && (
          <ul className={styles.list} data-testid="notifications-list">
            {rows.map((n) => (
              <li
                key={n.id}
                className={`${styles.row} ${n.read ? '' : styles.rowUnread}`}
                data-testid={`notification-${n.id}`}
              >
                <div className={styles.rowMain}>
                  <span className={styles.rowType}>{NOTIFICATION_TYPE_LABEL[n.type]}</span>
                  <p className={styles.rowTitle}>{n.title}</p>
                  <p className={styles.rowBody}>{n.body}</p>
                  <span className={styles.rowMeta}>{format.formatDateTime(n.createdAt)}</span>
                </div>
                <div className={styles.rowActions}>
                  {!n.read && (
                    <QlButton
                      size="sm"
                      variant="ghost"
                      loading={markRead.isPending && markRead.variables === n.id}
                      onClick={() => markRead.mutate(n.id)}
                    >
                      Mark read
                    </QlButton>
                  )}
                  <QlButton
                    size="sm"
                    variant="ghost"
                    loading={dismiss.isPending && dismiss.variables === n.id}
                    onClick={() => dismiss.mutate(n.id)}
                  >
                    Dismiss
                  </QlButton>
                </div>
              </li>
            ))}
          </ul>
        )}
        {(markRead.isError || dismiss.isError) && (
          <p className={styles.error} role="alert">
            {notificationsErrorCopy((markRead.error ?? dismiss.error)!)}
          </p>
        )}

        {meta && meta.total > meta.limit && (
          <div className={styles.pager}>
            <QlButton
              size="sm"
              variant="ghost"
              disabled={page === 0}
              onClick={() => setPage((p) => Math.max(0, p - 1))}
            >
              Previous
            </QlButton>
            <span className={styles.hint}>
              Page {page + 1} of {Math.max(1, Math.ceil(meta.total / meta.limit))}
            </span>
            <QlButton
              size="sm"
              variant="ghost"
              disabled={(page + 1) * meta.limit >= meta.total}
              onClick={() => setPage((p) => p + 1)}
            >
              Next
            </QlButton>
          </div>
        )}
      </QlCard>
    </div>
  )
}
