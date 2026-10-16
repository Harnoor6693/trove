/**
 * The household admin console — members, registration and the audit log
 * (§4.2, step 205).
 *
 * ## The role gate is cosmetic and is written that way on purpose
 *
 * Every operation here declares `x-trove-required-role: ADMIN`. This component
 * checks the role from the server's own `me` response purely so a MEMBER's
 * Settings page does not fire four pointless 403s — the SERVER is what refuses,
 * always. A member who reaches this surface anyway (a stale `me`, a demotion
 * mid-session, someone poking at the store) gets a clean, explained 403 state
 * rather than a broken page: that is what `retry: false` plus the forbidden
 * branch below are for.
 *
 * ## The refusals that are not errors
 *
 *  - **The household's last `ADMIN` is 409 `conflict`** on delete — *"an install
 *    whose only administrator is gone has no way back in"*. Shown as an
 *    explanation with the way forward, not as a failure.
 *  - **A self-target on unlock or clear-TOTP is 403 `step-up-required`**, so
 *    that *"an admin route never acts on the admin's own security state"* is one
 *    rule rather than a list of exceptions. The UI says so up front, next to the
 *    admin's own row, so nobody is told to "try again" at something that can
 *    never work.
 *  - **Restore outside the grace is 409 `conflict`** — the row is gone or about
 *    to be, and there is nothing left to restore.
 *
 * ## Two details worth not getting wrong
 *
 *  - `requestPasswordResetLink` lives HERE, not on the login page, because the
 *    contract makes it admin-scoped: there is no mail server on a typical
 *    install, so a reset is an admin minting a one-hour link and handing it over
 *    out of band. The response is the only time the link is readable, so it is
 *    shown once and never cached.
 *  - The audit log's `path`, `requestId` and every other field are rendered as
 *    TEXT. `path` is the matched route template rather than a raw URI precisely
 *    because two routes carry a live credential in the path; nothing here is
 *    ever put into markup.
 */
import { useState } from 'react'
import { QlBadge } from '@components/ui/QlBadge/QlBadge'
import { QlButton } from '@components/ui/QlButton/QlButton'
import { QlCard } from '@components/ui/QlCard/QlCard'
import { QlModal } from '@components/ui/QlModal/QlModal'
import { QlSkeleton } from '@components/ui/QlSkeleton/QlSkeleton'
import { QlTable, type QlTableColumn } from '@components/ui/QlTable/QlTable'
import { EmptyState } from '@components/EmptyState/EmptyState'
import { useDialogFocus } from '@hooks/useDialogFocus'
import { useUserFormat } from '@hooks/useUserFormat'
import { useMintResetLinkMutation } from '@hooks/useAccountSecurity'
import {
  useAdminSettings,
  useAuditLog,
  useClearMemberTotpMutation,
  useDeleteMemberMutation,
  useDemoteMemberMutation,
  useHouseholdMembers,
  usePromoteMemberMutation,
  useRestoreMemberMutation,
  useUnlockMemberMutation,
  useUpdateAdminSettingsMutation,
} from '@hooks/useAdmin'
import { useAuthStore } from '@stores/authStore'
import { adminErrorCopy } from './accountErrorCopy'
import type { AdminUserResponse, AuditLogEntry } from '@api/types'
import styles from './Settings.module.css'

/** The member actions, with the copy each confirmation needs. */
type MemberAction =
  'delete' | 'restore' | 'unlock' | 'clearTotp' | 'promote' | 'demote' | 'resetLink'

const ACTION_COPY: Record<
  Exclude<MemberAction, 'resetLink'>,
  { title: string; confirm: string; body: string }
> = {
  delete: {
    title: 'Remove this member?',
    confirm: 'Remove member',
    body: 'Their account is disabled immediately and every session of theirs ends at once. You can restore them for seven days, after which the account is erased for good. The household keeps its shared ledger, with their name removed from the rows that recorded who entered them. Their email address stays reserved until the erasure runs.',
  },
  restore: {
    title: 'Restore this member?',
    confirm: 'Restore member',
    body: 'This works for a member who was removed within the last seven days: their account becomes active again and they sign in with the credentials they already had. Their old sessions are not brought back — those were revoked when the account was removed and cannot be un-revoked. A member who was deactivated rather than removed, or whose seven days have passed, cannot be restored this way, and Trove will say so rather than change anything.',
  },
  unlock: {
    title: 'Clear this sign-in lock?',
    confirm: 'Clear lock',
    body: 'Repeated wrong passwords lock an account for a long time. This clears that lock so they can try again now. It does not change their password and it does not tell you what their password is.',
  },
  clearTotp: {
    title: 'Remove this member’s second factor?',
    confirm: 'Remove second factor',
    body: 'Use this when somebody has lost the phone their authenticator app was on. Their account will be protected by its password alone until they set two-factor up again. Trove records that YOU removed it, not that they did.',
  },
  promote: {
    title: 'Make this member an administrator?',
    confirm: 'Make administrator',
    body: 'Administrators can invite and remove members, open or close registration, change these settings and read the audit log. The new role reaches their session the next time it refreshes, within about fifteen minutes, or when they next sign in.',
  },
  demote: {
    title: 'Remove this member’s administrator role?',
    confirm: 'Remove administrator role',
    body: 'They become an ordinary member. Trove signs them out everywhere at once, so the old role cannot linger in a live session, and they sign in again as a member. Trove will not demote the household’s last administrator.',
  },
}

/* ---------------------------------------------------------------- members */

function MemberActionDialog({
  member,
  action,
  onClose,
}: {
  member: AdminUserResponse
  action: Exclude<MemberAction, 'resetLink'>
  onClose: () => void
}) {
  const ref = useDialogFocus<HTMLDivElement>(true)
  const remove = useDeleteMemberMutation()
  const restore = useRestoreMemberMutation()
  const unlock = useUnlockMemberMutation()
  const clearTotp = useClearMemberTotpMutation()
  const promote = usePromoteMemberMutation()
  const demote = useDemoteMemberMutation()
  const mutation = { delete: remove, restore, unlock, clearTotp, promote, demote }[action]
  const copy = ACTION_COPY[action]
  const destructive = action === 'delete' || action === 'clearTotp' || action === 'demote'

  // A 409 here is an explanation, not a failure: the last admin (delete or
  // demote), or a member
  // whose grace has already passed.
  const explanatory = mutation.isError && mutation.error.code === 'conflict'

  return (
    <QlModal isOpen onClose={onClose} title={copy.title}>
      <div ref={ref} tabIndex={-1} className={styles.stack} data-testid="member-action-dialog">
        <p className={styles.note}>
          {member.displayName} ({member.email})
        </p>
        <p className={destructive ? styles.warning : styles.note}>{copy.body}</p>
        {mutation.isError && (
          <p
            className={explanatory ? styles.note : styles.error}
            role={explanatory ? 'status' : 'alert'}
            data-testid="member-action-error"
          >
            {adminErrorCopy(mutation.error)}
          </p>
        )}
        <div className={styles.dialogActions}>
          <QlButton variant="primary" onClick={onClose}>
            Cancel
          </QlButton>
          <QlButton
            variant={destructive ? 'danger' : 'ghost'}
            loading={mutation.isPending}
            onClick={() => mutation.mutate(member.id, { onSuccess: onClose })}
          >
            {copy.confirm}
          </QlButton>
        </div>
      </div>
    </QlModal>
  )
}

function ResetLinkDialog({ member, onClose }: { member: AdminUserResponse; onClose: () => void }) {
  const mint = useMintResetLinkMutation()
  const ref = useDialogFocus<HTMLDivElement>(true)
  // The link panel replaces the form, unmounting the button that had focus, so
  // it takes focus itself — the one moment the content must be read and copied
  // before it is gone.
  const shownRef = useDialogFocus<HTMLDivElement>(mint.data !== undefined)
  const format = useUserFormat()

  if (mint.data) {
    return (
      <QlModal isOpen onClose={onClose} title="Reset link">
        <div ref={shownRef} tabIndex={-1} className={styles.stack} data-testid="reset-link-shown">
          <p className={styles.warning} role="alert">
            This is the only time Trove will show you this link. Copy it now and hand it to{' '}
            {member.displayName} yourself — Trove has no mail server, and anyone who has this link
            can set their password.
          </p>
          <p className={styles.codeBlock} data-testid="reset-link-url">
            {mint.data.url}
          </p>
          <p className={styles.note}>
            It works once and expires {format.formatDateTime(mint.data.expiresAt)}.
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
    <QlModal isOpen onClose={onClose} title="Create a password reset link">
      <div ref={ref} tabIndex={-1} className={styles.stack} data-testid="reset-link-dialog">
        <p className={styles.note}>
          Trove has no mail server, so it cannot send a reset email. Instead you mint a one-hour,
          single-use link for {member.email} and hand it over yourself, through a channel you trust.
        </p>
        {mint.isError && (
          <p className={styles.error} role="alert">
            {adminErrorCopy(mint.error)}
          </p>
        )}
        <div className={styles.dialogActions}>
          <QlButton variant="ghost" onClick={onClose}>
            Cancel
          </QlButton>
          <QlButton
            variant="primary"
            loading={mint.isPending}
            onClick={() => mint.mutate({ email: member.email })}
          >
            Create link
          </QlButton>
        </div>
      </div>
    </QlModal>
  )
}

function MembersPanel() {
  const members = useHouseholdMembers(true)
  const format = useUserFormat()
  const myId = useAuthStore((s) => s.user?.userId)
  const [target, setTarget] = useState<{ member: AdminUserResponse; action: MemberAction } | null>(
    null
  )

  const columns: QlTableColumn<AdminUserResponse & Record<string, unknown>>[] = [
    { key: 'displayName', header: 'Member' },
    { key: 'email', header: 'Email' },
    { key: 'role', header: 'Role' },
    // `active: false` is TWO states the contract does not let a client tell
    // apart: "removed, inside the seven-day grace" and "merely deactivated",
    // the second of which `restore` refuses with 409 ("A restore of a member
    // who was merely deactivated rather than deleted answers 409"). There is no
    // `deletedAt` on `AdminUserResponse` to separate them, so the badge says
    // only what `active` itself says; the grace window and the refusal live in
    // the Restore dialog, which is also where the 409 lands.
    {
      key: 'active',
      header: 'State',
      render: (row) => (
        <QlBadge variant={row.active ? 'sage' : 'amber'}>
          {row.active ? 'Active' : 'Cannot sign in'}
        </QlBadge>
      ),
    },
    {
      key: 'totpEnabled',
      header: 'Two-factor',
      render: (row) => (row.totpEnabled ? 'On' : 'Off'),
    },
    { key: 'createdAt', header: 'Joined', render: (row) => format.formatDate(row.createdAt) },
    {
      key: 'actions',
      header: 'Actions',
      render: (row) => {
        const isSelf = row.id === myId
        return (
          <div className={styles.rowActions}>
            {row.active ? (
              <QlButton
                size="sm"
                variant="ghost"
                onClick={() => setTarget({ member: row, action: 'delete' })}
              >
                Remove
              </QlButton>
            ) : (
              <QlButton
                size="sm"
                variant="ghost"
                onClick={() => setTarget({ member: row, action: 'restore' })}
              >
                Restore
              </QlButton>
            )}
            {/* Unlock and clear-TOTP refuse a self-target with 403
                `step-up-required`, so they are not offered on the admin's own
                row at all — offering them would be inviting a refusal. */}
            {/* No role change on the admin's own row: handing over is "make
                somebody else an administrator", and the server keeps the last
                one regardless. */}
            {!isSelf && row.active && (
              <QlButton
                size="sm"
                variant="ghost"
                onClick={() =>
                  setTarget({ member: row, action: row.role === 'ADMIN' ? 'demote' : 'promote' })
                }
              >
                {row.role === 'ADMIN' ? 'Remove administrator role' : 'Make administrator'}
              </QlButton>
            )}
            {!isSelf && (
              <>
                <QlButton
                  size="sm"
                  variant="ghost"
                  onClick={() => setTarget({ member: row, action: 'unlock' })}
                >
                  Clear lock
                </QlButton>
                {row.totpEnabled && (
                  <QlButton
                    size="sm"
                    variant="ghost"
                    onClick={() => setTarget({ member: row, action: 'clearTotp' })}
                  >
                    Remove two-factor
                  </QlButton>
                )}
              </>
            )}
            <QlButton
              size="sm"
              variant="ghost"
              onClick={() => setTarget({ member: row, action: 'resetLink' })}
            >
              Reset link
            </QlButton>
          </div>
        )
      },
    },
  ]

  return (
    <div className={styles.stack}>
      <h3 className={styles.sectionTitle}>Household members</h3>
      <p className={styles.note}>
        Trove will not let you remove or demote the household&apos;s last administrator — make
        another member an administrator first — and it will not let you clear your own sign-in lock
        or your own second factor from here — an administrator whose session has been stolen must
        not be able to undo their own protections with it. Use your own Account security section, or
        the server console.
      </p>
      {members.isPending && <QlSkeleton data-testid="members-skeleton" />}
      {members.isError && (
        <p className={styles.error} role="alert" data-testid="members-error">
          {adminErrorCopy(members.error)}
        </p>
      )}
      {members.data && members.data.length > 0 && (
        <div className={styles.tableWrap} data-testid="members-table">
          <QlTable
            columns={columns}
            rows={members.data as (AdminUserResponse & Record<string, unknown>)[]}
          />
        </div>
      )}
      {target && target.action === 'resetLink' && (
        <ResetLinkDialog member={target.member} onClose={() => setTarget(null)} />
      )}
      {target && target.action !== 'resetLink' && (
        <MemberActionDialog
          member={target.member}
          action={target.action}
          onClose={() => setTarget(null)}
        />
      )}
    </div>
  )
}

/* -------------------------------------------------------------- settings */

/**
 * Opening registration is confirmed in the page, not with `window.confirm`: the
 * consequence needs saying in full, and a native dialog can neither be styled
 * nor reliably read by every screen reader.
 */
function OpenRegistrationDialog({
  pending,
  onConfirm,
  onCancel,
}: {
  pending: boolean
  onConfirm: () => void
  onCancel: () => void
}) {
  const ref = useDialogFocus<HTMLDivElement>(true)
  return (
    <QlModal isOpen onClose={onCancel} title="Open registration?">
      <div ref={ref} tabIndex={-1} className={styles.stack} data-testid="open-registration-dialog">
        <p className={styles.warning}>
          Anyone who can reach this server will be able to create an account, without an invite. A
          Trove server has one household, so every new account joins yours and sees its whole shared
          ledger: every account, transaction, budget and receipt.
        </p>
        <p className={styles.note}>
          Open it only while you expect somebody to sign up, and close it again afterwards.
        </p>
        <div className={styles.dialogActions}>
          <QlButton variant="primary" onClick={onCancel}>
            Keep it closed
          </QlButton>
          <QlButton variant="danger" loading={pending} onClick={onConfirm}>
            Open registration
          </QlButton>
        </div>
      </div>
    </QlModal>
  )
}

function RegistrationPanel() {
  const settings = useAdminSettings(true)
  const update = useUpdateAdminSettingsMutation()
  const [confirming, setConfirming] = useState(false)

  return (
    <div className={styles.stack}>
      <h3 className={styles.sectionTitle}>Registration</h3>
      <p className={styles.note} data-testid="registration-note">
        With registration closed, the only way in is an invite link you create. Open it, and anyone
        who can reach this server can create an account, and every account joins this household —
        there is only one — so it sees the whole shared ledger. The setting is stored on the
        household, so it survives a restart.
      </p>
      {settings.data?.registrationOpen && (
        <p className={styles.warning} role="status" data-testid="registration-open-notice">
          Registration is open. Anyone who finds this server can sign up and see everything in this
          household. Close it once the people you expect have joined.
        </p>
      )}
      {settings.isError && (
        <p className={styles.error} role="alert">
          {adminErrorCopy(settings.error)}
        </p>
      )}
      {settings.data && (
        <label className={styles.row}>
          <span className={styles.label}>Anyone may sign up without an invitation</span>
          <input
            type="checkbox"
            checked={settings.data.registrationOpen}
            disabled={update.isPending}
            onChange={(e) => {
              // Closing needs no confirm; opening does, and nothing is written until it is given.
              if (e.target.checked) setConfirming(true)
              else update.mutate({ registrationOpen: false })
            }}
          />
        </label>
      )}
      {update.isError && (
        <p className={styles.error} role="alert">
          {adminErrorCopy(update.error)}
        </p>
      )}
      {confirming && (
        <OpenRegistrationDialog
          pending={update.isPending}
          onCancel={() => setConfirming(false)}
          onConfirm={() =>
            update.mutate({ registrationOpen: true }, { onSettled: () => setConfirming(false) })
          }
        />
      )}
    </div>
  )
}

/**
 * The scheduled bank sync switch (C-01r). Off by default; the server reads it on
 * every run, so turning it off stops the next one.
 */
function ScheduledSyncPanel() {
  const settings = useAdminSettings(true)
  const update = useUpdateAdminSettingsMutation()

  return (
    <div className={styles.stack}>
      <h3 className={styles.sectionTitle}>Bank sync</h3>
      <p className={styles.note} data-testid="scheduled-sync-note">
        When this is on, the server syncs every SimpleFIN-connected account once a day, so nobody
        has to press Sync. If a connection stops working, the person who connected it is told
        only once, not every day.
      </p>
      {settings.data && (
        <label className={styles.row}>
          <span className={styles.label}>Sync connected banks every day</span>
          <input
            type="checkbox"
            checked={settings.data.scheduledSyncEnabled}
            disabled={update.isPending}
            onChange={(e) => update.mutate({ scheduledSyncEnabled: e.target.checked })}
          />
        </label>
      )}
      {update.isError && (
        <p className={styles.error} role="alert">
          {adminErrorCopy(update.error)}
        </p>
      )}
    </div>
  )
}

/* ------------------------------------------------------------- audit log */

function AuditPanel() {
  const [page, setPage] = useState(0)
  const audit = useAuditLog({ page, limit: 20 }, true)
  const format = useUserFormat()

  const columns: QlTableColumn<AuditLogEntry & Record<string, unknown>>[] = [
    { key: 'at', header: 'When', render: (row) => format.formatDateTime(row.at) },
    { key: 'method', header: 'Method' },
    // Rendered as TEXT. `path` is the matched route template, and nothing in an
    // audit row is ever interpreted as markup.
    { key: 'path', header: 'Route', render: (row) => <code>{row.path}</code> },
    { key: 'status', header: 'Status', render: (row) => String(row.status) },
    { key: 'userId', header: 'Signed in as', render: (row) => row.userId ?? 'anonymous' },
    { key: 'clientIp', header: 'From', render: (row) => row.clientIp ?? 'not recorded' },
    { key: 'durationMs', header: 'Took', render: (row) => `${row.durationMs} ms` },
  ]

  const rows = audit.data?.rows ?? []
  const meta = audit.data?.meta

  return (
    <div className={styles.stack}>
      <h3 className={styles.sectionTitle}>Audit log</h3>
      <p className={styles.note}>
        One row for every request that changed something, newest first. Reads are not recorded, and
        no request body, header, query string or token is ever kept.
      </p>
      {audit.isPending && <QlSkeleton data-testid="audit-skeleton" />}
      {audit.isError && (
        <p className={styles.error} role="alert">
          {adminErrorCopy(audit.error)}
        </p>
      )}
      {audit.data && rows.length === 0 && (
        <EmptyState title="Nothing recorded yet">
          <p>Changes to this household will appear here.</p>
        </EmptyState>
      )}
      {audit.data && rows.length > 0 && (
        <div className={styles.tableWrap} data-testid="audit-table">
          <QlTable columns={columns} rows={rows as (AuditLogEntry & Record<string, unknown>)[]} />
        </div>
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
          <span className={styles.note} data-testid="audit-page">
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
    </div>
  )
}

/* -------------------------------------------------------------- section */

/** True when the server's own `me` says so. Cosmetic — the server refuses regardless. */
function useIsAdmin(): boolean {
  return useAuthStore((s) => s.user?.role) === 'ADMIN'
}

export function AdminSection() {
  const isAdmin = useIsAdmin()

  if (!isAdmin) {
    return (
      <QlCard>
        <div className={styles.section} id="admin-console">
          <h2 className={styles.sectionTitle}>Administration</h2>
          <p className={styles.note} role="status" data-testid="admin-forbidden">
            Only an administrator of this household can manage members, registration and the audit
            log. Ask one of yours if you need something changed here.
          </p>
        </div>
      </QlCard>
    )
  }

  return (
    <QlCard>
      <div className={styles.section} id="admin-console">
        <h2 className={styles.sectionTitle}>Administration</h2>
        <MembersPanel />
        <RegistrationPanel />
        <ScheduledSyncPanel />
        <AuditPanel />
      </div>
    </QlCard>
  )
}
