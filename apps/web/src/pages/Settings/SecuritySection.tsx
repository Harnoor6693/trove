/**
 * Account security — the password change, the TOTP enrolment lifecycle, and the
 * member's own security-event history (§4.1, step 205).
 *
 * ## The three things this screen must get right
 *
 * 1. **`changePassword` keeps THIS session and ends every other one.** From the
 *    operation's own description: *"Ends every other session and leaves this one
 *    working. The response carries `{user}` and no tokens, and sets no cookies."*
 *    So the member is told exactly that, and is not signed out. A wrong current
 *    password is 403 `step-up-required`, not 401 — worth knowing because a 401
 *    would have sent the client interceptor off to refresh a perfectly good
 *    session.
 *
 * 2. **The recovery codes are readable exactly once.** `confirmTotp` mints them
 *    and they are stored hashed; *"a client that does not surface them here has
 *    lost them"*. They are shown once, from the mutation's own result, held in
 *    this component's state, never re-fetched, never written to a query cache
 *    and never persisted. Closing the dialog is the last time they exist.
 *
 * 3. **Enrolment is two steps on purpose.** `setupTotp` returns the secret with
 *    the factor still OFF; one live code through `confirmTotp` turns it on. The
 *    failure that ordering prevents is *"a user who scans a QR code badly, turns
 *    on two-factor, and locks themselves out of their own finance server with no
 *    reset path"*.
 *
 * Every credential on this screen goes through `useCredentialMutation`, so no
 * password and no code is ever a mutation variable — see that hook for why the
 * ordinary way leaks them into the mutation cache for the gc window.
 *
 * Disabling uses `POST /v1/auth/totp/disable`, not `DELETE /v1/auth/totp`: the
 * two are the same operation, and the spec says of the POST twin, "**This is the
 * one that always works**" — a DELETE body is legal but intermediaries drop it.
 */
import { useState } from 'react'
import { QlBadge } from '@components/ui/QlBadge/QlBadge'
import { QlButton } from '@components/ui/QlButton/QlButton'
import { QlCard } from '@components/ui/QlCard/QlCard'
import { QlInput } from '@components/ui/QlInput/QlInput'
import { QlModal } from '@components/ui/QlModal/QlModal'
import { QlSkeleton } from '@components/ui/QlSkeleton/QlSkeleton'
import { QlTable, type QlTableColumn } from '@components/ui/QlTable/QlTable'
import { EmptyState } from '@components/EmptyState/EmptyState'
import { useDialogFocus } from '@hooks/useDialogFocus'
import { useUserFormat } from '@hooks/useUserFormat'
import {
  useChangePasswordMutation,
  useConfirmTotpMutation,
  useDisableTotpMutation,
  useSecurityEvents,
  useSetupTotpMutation,
} from '@hooks/useAccountSecurity'
import { useAuthStore } from '@stores/authStore'
import { securityErrorCopy } from './accountErrorCopy'
import type { SecurityEventView, TotpEnrolment } from '@api/types'
import styles from './Settings.module.css'

/* --------------------------------------------------------- change password */

function ChangePasswordDialog({ onClose }: { onClose: () => void }) {
  const change = useChangePasswordMutation()
  const ref = useDialogFocus<HTMLDivElement>(true)
  const [currentPassword, setCurrentPassword] = useState('')
  const [newPassword, setNewPassword] = useState('')
  const [done, setDone] = useState(false)
  // The panel that REPLACES the form is a second dialog surface as far as focus
  // is concerned: the button that had focus unmounts in the same commit, so
  // without this focus falls back to <body> and a keyboard user is left outside
  // the dialog. The hook keys on the branch, so it fires when the branch opens.
  const doneRef = useDialogFocus<HTMLDivElement>(done)

  function submit(e: React.FormEvent) {
    e.preventDefault()
    change.submit(
      { currentPassword, newPassword },
      {
        onSuccess: () => {
          // Neither value outlives the call it was made for.
          setCurrentPassword('')
          setNewPassword('')
          setDone(true)
        },
      }
    )
  }

  if (done) {
    return (
      <QlModal isOpen onClose={onClose} title="Password changed">
        <div ref={doneRef} tabIndex={-1} className={styles.stack} data-testid="password-changed">
          <p className={styles.note}>
            Your password is changed. You are still signed in here — every other session on this
            account has been signed out and will need the new password.
          </p>
          <div className={styles.dialogActions}>
            <QlButton variant="primary" onClick={onClose}>
              Done
            </QlButton>
          </div>
        </div>
      </QlModal>
    )
  }

  return (
    <QlModal isOpen onClose={onClose} title="Change your password">
      <div ref={ref} tabIndex={-1} className={styles.stack} data-testid="change-password-dialog">
        <p className={styles.note}>
          Changing your password signs out every other session on this account. This one keeps
          working.
        </p>
        <form className={styles.stack} onSubmit={submit}>
          <QlInput
            label="Current password"
            type="password"
            autoComplete="current-password"
            required
            value={currentPassword}
            onChange={(e) => setCurrentPassword(e.target.value)}
          />
          <QlInput
            label="New password"
            type="password"
            autoComplete="new-password"
            required
            value={newPassword}
            onChange={(e) => setNewPassword(e.target.value)}
            error={change.error?.fieldError('newPassword')}
          />
          {change.isError && !change.error.fieldError('newPassword') && (
            <p className={styles.error} role="alert">
              {securityErrorCopy(change.error)}
            </p>
          )}
          <div className={styles.dialogActions}>
            <QlButton type="button" variant="ghost" onClick={onClose}>
              Cancel
            </QlButton>
            <QlButton type="submit" variant="primary" loading={change.isPending}>
              Change password
            </QlButton>
          </div>
        </form>
      </div>
    </QlModal>
  )
}

/* ------------------------------------------------------------ TOTP enrol */

function EnrolTotpDialog({ onClose }: { onClose: () => void }) {
  const setup = useSetupTotpMutation()
  const confirm = useConfirmTotpMutation()
  const setUser = useAuthStore((s) => s.setUser)
  const user = useAuthStore((s) => s.user)
  const ref = useDialogFocus<HTMLDivElement>(true)
  const [password, setPassword] = useState('')
  const [code, setCode] = useState('')
  const [enrolment, setEnrolment] = useState<TotpEnrolment | null>(null)
  // Shown ONCE, from the confirm response, and never read back from anywhere.
  const [recoveryCodes, setRecoveryCodes] = useState<string[] | null>(null)
  // Focus follows the swap to the codes panel: the button that had focus is
  // unmounted in the same commit, and this is the one moment the content is
  // something the member MUST read and copy before it is gone for good.
  const codesRef = useDialogFocus<HTMLDivElement>(recoveryCodes !== null)

  function startEnrolment(e: React.FormEvent) {
    e.preventDefault()
    setup.submit(
      { password },
      {
        onSuccess: (data) => {
          setPassword('')
          setEnrolment(data)
        },
      }
    )
  }

  function confirmEnrolment(e: React.FormEvent) {
    e.preventDefault()
    confirm.submit(
      { code },
      {
        onSuccess: (data) => {
          setCode('')
          setEnrolment(null)
          setRecoveryCodes(data.recoveryCodes)
          if (user) setUser({ ...user, totpEnabled: true })
        },
      }
    )
  }

  if (recoveryCodes) {
    return (
      <QlModal isOpen onClose={onClose} title="Save your recovery codes">
        <div ref={codesRef} tabIndex={-1} className={styles.stack} data-testid="recovery-codes">
          <p className={styles.warning} role="alert">
            This is the only time Trove can show you these. They are stored hashed, so nobody —
            including an administrator — can read them back. Save them somewhere safe now: each one
            signs you in once if you lose your authenticator app.
          </p>
          <ul className={styles.codeList}>
            {recoveryCodes.map((rc) => (
              <li key={rc}>{rc}</li>
            ))}
          </ul>
          <div className={styles.dialogActions}>
            <QlButton variant="primary" onClick={onClose}>
              I have saved them
            </QlButton>
          </div>
        </div>
      </QlModal>
    )
  }

  if (enrolment) {
    return (
      <QlModal isOpen onClose={onClose} title="Scan this into your authenticator">
        <div ref={ref} tabIndex={-1} className={styles.stack} data-testid="totp-enrolment">
          <p className={styles.note}>
            Two-factor authentication is <strong>not on yet</strong>. Add this secret to your
            authenticator app, then type one code from it to turn it on — that way a mistyped secret
            is caught now rather than the next time you sign in.
          </p>
          <p className={styles.label}>Secret, for manual entry</p>
          <p className={styles.codeBlock} data-testid="totp-secret">
            {enrolment.secret}
          </p>
          <p className={styles.note}>
            {enrolment.digits}-digit codes, rotating every {enrolment.periodSeconds} seconds.
          </p>
          <form className={styles.stack} onSubmit={confirmEnrolment}>
            <QlInput
              label="Code from your authenticator"
              name="code"
              inputMode="numeric"
              autoComplete="one-time-code"
              required
              value={code}
              onChange={(e) => setCode(e.target.value)}
            />
            {confirm.isError && (
              <p className={styles.error} role="alert">
                {securityErrorCopy(confirm.error)}
              </p>
            )}
            <div className={styles.dialogActions}>
              <QlButton type="button" variant="ghost" onClick={onClose}>
                Cancel
              </QlButton>
              <QlButton type="submit" variant="primary" loading={confirm.isPending}>
                Turn on two-factor
              </QlButton>
            </div>
          </form>
        </div>
      </QlModal>
    )
  }

  return (
    <QlModal isOpen onClose={onClose} title="Set up two-factor authentication">
      <div ref={ref} tabIndex={-1} className={styles.stack} data-testid="totp-password-dialog">
        <p className={styles.note}>
          Confirm your password to start. Nothing changes about your account until you enter a
          working code on the next step.
        </p>
        <form className={styles.stack} onSubmit={startEnrolment}>
          <QlInput
            label="Current password"
            type="password"
            autoComplete="current-password"
            required
            value={password}
            onChange={(e) => setPassword(e.target.value)}
          />
          {setup.isError && (
            <p className={styles.error} role="alert">
              {securityErrorCopy(setup.error)}
            </p>
          )}
          <div className={styles.dialogActions}>
            <QlButton type="button" variant="ghost" onClick={onClose}>
              Cancel
            </QlButton>
            <QlButton type="submit" variant="primary" loading={setup.isPending}>
              Continue
            </QlButton>
          </div>
        </form>
      </div>
    </QlModal>
  )
}

/* ---------------------------------------------------------- TOTP disable */

function DisableTotpDialog({ onClose }: { onClose: () => void }) {
  const disable = useDisableTotpMutation()
  const setUser = useAuthStore((s) => s.setUser)
  const user = useAuthStore((s) => s.user)
  const ref = useDialogFocus<HTMLDivElement>(true)
  const [password, setPassword] = useState('')
  const [code, setCode] = useState('')

  function submit(e: React.FormEvent) {
    e.preventDefault()
    disable.submit(
      { password, code },
      {
        onSuccess: () => {
          setPassword('')
          setCode('')
          if (user) setUser({ ...user, totpEnabled: false })
          onClose()
        },
      }
    )
  }

  return (
    <QlModal isOpen onClose={onClose} title="Turn off two-factor authentication?">
      <div ref={ref} tabIndex={-1} className={styles.stack} data-testid="disable-totp-dialog">
        <p className={styles.warning}>
          Your account will be protected by its password alone. Trove asks for both your password
          and a current code here, because either one on its own would let through exactly the
          person the second factor exists to stop.
        </p>
        <form className={styles.stack} onSubmit={submit}>
          <QlInput
            label="Current password"
            type="password"
            autoComplete="current-password"
            required
            value={password}
            onChange={(e) => setPassword(e.target.value)}
          />
          <QlInput
            label="Code from your authenticator"
            inputMode="numeric"
            autoComplete="one-time-code"
            required
            value={code}
            onChange={(e) => setCode(e.target.value)}
          />
          {disable.isError && (
            <p className={styles.error} role="alert">
              {securityErrorCopy(disable.error)}
            </p>
          )}
          <div className={styles.dialogActions}>
            <QlButton type="button" variant="ghost" onClick={onClose}>
              Keep it on
            </QlButton>
            <QlButton type="submit" variant="danger" loading={disable.isPending}>
              Turn off two-factor
            </QlButton>
          </div>
        </form>
      </div>
    </QlModal>
  )
}

/* ------------------------------------------------------- security events */

function SecurityEvents() {
  const events = useSecurityEvents()
  const format = useUserFormat()

  const columns: QlTableColumn<SecurityEventView & Record<string, unknown>>[] = [
    // `type` is a plain string in the contract, not an enum — later steps add
    // kinds, so an unknown one is rendered as it arrived rather than dropped.
    { key: 'type', header: 'Event' },
    { key: 'at', header: 'When', render: (row) => format.formatDateTime(row.at) },
    { key: 'ip', header: 'From', render: (row) => row.ip ?? 'not recorded' },
    { key: 'userAgent', header: 'Device', render: (row) => row.userAgent ?? 'not recorded' },
  ]

  return (
    <div className={styles.stack}>
      <h3 className={styles.sectionTitle}>Recent security activity</h3>
      <p className={styles.note}>
        Your own account only, newest first. Nobody had to ask an administrator to see this.
      </p>
      {events.isPending && <QlSkeleton data-testid="security-events-skeleton" />}
      {events.isError && (
        <p className={styles.error} role="alert">
          {securityErrorCopy(events.error)}
        </p>
      )}
      {events.data && events.data.length === 0 && (
        <EmptyState title="Nothing recorded yet">
          <p>Sign-ins and security changes will appear here.</p>
        </EmptyState>
      )}
      {events.data && events.data.length > 0 && (
        <div className={styles.tableWrap} data-testid="security-events-table">
          <QlTable
            columns={columns}
            rows={events.data as (SecurityEventView & Record<string, unknown>)[]}
          />
        </div>
      )}
    </div>
  )
}

/* ------------------------------------------------------------- section */

export function SecuritySection() {
  const user = useAuthStore((s) => s.user)
  const [dialog, setDialog] = useState<'password' | 'enrol' | 'disable' | null>(null)
  const totpEnabled = user?.totpEnabled ?? false

  return (
    <QlCard>
      <div className={styles.section} id="account-security">
        <h2 className={styles.sectionTitle}>Account security</h2>

        <div className={styles.row}>
          <span className={styles.label}>Password</span>
          <QlButton size="sm" variant="ghost" onClick={() => setDialog('password')}>
            Change password
          </QlButton>
        </div>

        <div className={styles.row}>
          <span className={styles.label}>Two-factor authentication</span>
          <span className={styles.rowActions}>
            <QlBadge variant={totpEnabled ? 'sage' : 'neutral'}>
              {totpEnabled ? 'On' : 'Off'}
            </QlBadge>
            {totpEnabled ? (
              <QlButton size="sm" variant="ghost" onClick={() => setDialog('disable')}>
                Turn off
              </QlButton>
            ) : (
              <QlButton size="sm" variant="ghost" onClick={() => setDialog('enrol')}>
                Set up two-factor
              </QlButton>
            )}
          </span>
        </div>

        <SecurityEvents />

        {dialog === 'password' && <ChangePasswordDialog onClose={() => setDialog(null)} />}
        {dialog === 'enrol' && <EnrolTotpDialog onClose={() => setDialog(null)} />}
        {dialog === 'disable' && <DisableTotpDialog onClose={() => setDialog(null)} />}
      </div>
    </QlCard>
  )
}
