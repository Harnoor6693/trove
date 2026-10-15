/**
 * Notification preferences (§4.9, step 203). `PUT /v1/notifications/preferences`
 * is a partial update despite the verb — every field here is sent on save, which
 * is harmless (an unset value would leave the row unchanged, but this form
 * always has one) EXCEPT `emailAddress`: leaving the field blank sends an
 * explicit `null`, the one way to clear it.
 *
 * There is deliberately no push field anywhere in this section — no toggle for
 * a device notification of any kind. That is a settled owner decision
 * (2026-09-03), not an omission.
 */
import { useState } from 'react'
import { QlButton } from '@components/ui/QlButton/QlButton'
import { QlCard } from '@components/ui/QlCard/QlCard'
import { QlInput } from '@components/ui/QlInput/QlInput'
import {
  useNotificationPreferences,
  useUpdateNotificationPreferencesMutation,
} from '@hooks/useNotifications'
import { notificationsErrorCopy } from '../Notifications/errorCopy'
import type { NotificationPreferencesResponse } from '@api/types'
import styles from './Settings.module.css'

interface FormState {
  inAppEnabled: boolean
  emailEnabled: boolean
  emailAddress: string
  weeklyDigestEnabled: boolean
  subscriptionAlertEnabled: boolean
  syncFailureAlertEnabled: boolean
  budgetAlertThresholdPercent: string
}

function formFrom(prefs: NotificationPreferencesResponse): FormState {
  return {
    inAppEnabled: prefs.inAppEnabled,
    emailEnabled: prefs.emailEnabled,
    emailAddress: prefs.emailAddress ?? '',
    weeklyDigestEnabled: prefs.weeklyDigestEnabled,
    subscriptionAlertEnabled: prefs.subscriptionAlertEnabled,
    syncFailureAlertEnabled: prefs.syncFailureAlertEnabled,
    budgetAlertThresholdPercent: String(prefs.budgetAlertThresholdPercent),
  }
}

function NotificationsForm({ prefs }: { prefs: NotificationPreferencesResponse }) {
  const [form, setForm] = useState<FormState>(() => formFrom(prefs))
  const [saved, setSaved] = useState(false)
  const save = useUpdateNotificationPreferencesMutation()

  function set<K extends keyof FormState>(key: K, value: FormState[K]) {
    setSaved(false)
    setForm((prev) => ({ ...prev, [key]: value }))
  }

  function submit(e: React.FormEvent) {
    e.preventDefault()
    setSaved(false)
    save.mutate(
      {
        inAppEnabled: form.inAppEnabled,
        emailEnabled: form.emailEnabled,
        // A blank field is the member clearing it — the one field where an
        // explicit null differs from "leave alone".
        emailAddress: form.emailAddress.trim() === '' ? null : form.emailAddress.trim(),
        weeklyDigestEnabled: form.weeklyDigestEnabled,
        subscriptionAlertEnabled: form.subscriptionAlertEnabled,
        syncFailureAlertEnabled: form.syncFailureAlertEnabled,
        budgetAlertThresholdPercent: Number(form.budgetAlertThresholdPercent),
      },
      {
        onSuccess: (next) => {
          setForm(formFrom(next))
          setSaved(true)
        },
      }
    )
  }

  return (
    <form className={styles.section} onSubmit={submit}>
      <h2 className={styles.sectionTitle}>Notifications</h2>
      <p className={styles.note}>
        There is no push notification on Trove — no device token, no phone alert. This inbox and
        the bell in the top bar are the whole of it.
      </p>

      <label className={styles.label}>
        <input
          type="checkbox"
          checked={form.inAppEnabled}
          onChange={(e) => set('inAppEnabled', e.target.checked)}
        />{' '}
        Announce new notifications (the inbox row is kept either way)
      </label>
      <label className={styles.label}>
        <input
          type="checkbox"
          checked={form.weeklyDigestEnabled}
          onChange={(e) => set('weeklyDigestEnabled', e.target.checked)}
        />{' '}
        Weekly digest
      </label>
      <label className={styles.label}>
        <input
          type="checkbox"
          checked={form.subscriptionAlertEnabled}
          onChange={(e) => set('subscriptionAlertEnabled', e.target.checked)}
        />{' '}
        Subscription price changes
      </label>
      <label className={styles.label}>
        <input
          type="checkbox"
          checked={form.syncFailureAlertEnabled}
          onChange={(e) => set('syncFailureAlertEnabled', e.target.checked)}
        />{' '}
        Failed account syncs
      </label>

      <QlInput
        label="Budget alert threshold (%)"
        inputMode="numeric"
        value={form.budgetAlertThresholdPercent}
        onChange={(e) => set('budgetAlertThresholdPercent', e.target.value)}
        hint="How full an envelope gets before it tells you. Default 90."
        error={save.error?.fieldError('budgetAlertThresholdPercent')}
      />

      <label className={styles.label}>
        <input
          type="checkbox"
          checked={form.emailEnabled}
          onChange={(e) => set('emailEnabled', e.target.checked)}
        />{' '}
        Also email me
      </label>
      <QlInput
        label="Email address"
        type="email"
        value={form.emailAddress}
        onChange={(e) => set('emailAddress', e.target.value)}
        hint="Separate from your sign-in address. This server's email channel may be off — ask your administrator."
        error={save.error?.fieldError('emailAddress')}
      />

      {save.isError && (
        <p className={styles.error} role="alert" data-testid="notification-prefs-error">
          {notificationsErrorCopy(save.error)}
        </p>
      )}
      {saved && (
        <p className={styles.status} role="status" data-testid="notification-prefs-saved">
          Saved.
        </p>
      )}
      <div className={styles.actions}>
        <QlButton type="submit" variant="primary" loading={save.isPending}>
          Save notification preferences
        </QlButton>
      </div>
    </form>
  )
}

export function NotificationsSection() {
  const prefs = useNotificationPreferences()

  return (
    <QlCard>
      {prefs.isPending && (
        <div className={styles.section}>
          <h2 className={styles.sectionTitle}>Notifications</h2>
          <p className={styles.note} role="status">
            Loading your notification preferences…
          </p>
        </div>
      )}
      {prefs.isError && (
        <div className={styles.section}>
          <h2 className={styles.sectionTitle}>Notifications</h2>
          <p className={styles.error} role="alert" data-testid="notification-prefs-load-error">
            {notificationsErrorCopy(prefs.error)}
          </p>
        </div>
      )}
      {prefs.data && <NotificationsForm prefs={prefs.data} />}
    </QlCard>
  )
}
