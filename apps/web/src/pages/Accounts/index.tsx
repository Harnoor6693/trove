/**
 * Accounts — the household's connected accounts, and every action on one.
 *
 * This file replaced a twenty-line placeholder whose header recorded the rule it
 * was obeying: *no number that could be mistaken for the user's money may
 * originate anywhere but the API*. That rule still holds, and it decides one
 * visible thing here — **there is no balance column**. `AccountResponse` carries
 * no balance, no operation in `openapi/trove-core.yaml` answers one, and a
 * balance derived client-side from the transaction list would be a figure that
 * looks like the member's money and came from arithmetic this app invented. It
 * is recorded as a contract gap instead.
 *
 * Every currency shown is the account's own. There is no household total,
 * because there is no exchange rate anywhere in this product (owner decision 2).
 */
import { useEffect, useState } from 'react'
import { QlButton } from '@components/ui/QlButton/QlButton'
import { QlCard } from '@components/ui/QlCard/QlCard'
import { QlBadge } from '@components/ui/QlBadge/QlBadge'
import { QlInput } from '@components/ui/QlInput/QlInput'
import { QlModal } from '@components/ui/QlModal/QlModal'
import { QlSkeleton } from '@components/ui/QlSkeleton/QlSkeleton'
import { EmptyState } from '@components/EmptyState/EmptyState'
import { useUserFormat } from '@hooks/useUserFormat'
import { useDialogFocus } from '@hooks/useDialogFocus'
import {
  useAccount,
  useAccountSyncLogs,
  useAccounts,
  useCreateAccountMutation,
  useDeleteAccountMutation,
  useSyncAccountMutation,
  useUpdateAccountMutation,
} from '@hooks/useAccounts'
import { useUndoImportMutation } from '@hooks/useImports'
import {
  exchangeErrorCopy,
  useExchangeSetupTokenMutation,
} from '@hooks/useExchangeSetupTokenMutation'
import { ImportWizard } from './ImportWizard'
import { COUNTRY_PROFILES } from '@api/types'
import type { ApiError } from '@api/client'
import type {
  AccountResponse,
  AccountSyncStatus,
  AccountType,
  CurrencyCode,
  SourceKind,
  SyncLogResponse,
  SyncRunStatus,
} from '@api/types'
import styles from './Accounts.module.css'

/* ------------------------------------------------------------------ labels */

const ACCOUNT_TYPE_LABEL: Record<AccountType, string> = {
  CHECKING: 'Chequing',
  SAVINGS: 'Savings',
  CREDIT_CARD: 'Credit card',
  INVESTMENT: 'Investment',
  OTHER: 'Other',
}

const SOURCE_LABEL: Record<SourceKind, string> = {
  SIMPLEFIN: 'Bank connection',
  OFX: 'OFX file',
  CSV: 'CSV file',
  MANUAL: 'Entered by hand',
}

/**
 * Sync state carries a WORD, never a colour alone. The badge tone is decoration;
 * the text is the information, which is what an assistive technology and a
 * colour-blind member both read.
 */
const SYNC_LABEL: Record<AccountSyncStatus, string> = {
  PENDING: 'Not synced yet',
  SYNCING: 'Syncing now',
  OK: 'Synced',
  ERROR: 'Last sync failed',
}

const SYNC_TONE: Record<AccountSyncStatus, 'sage' | 'neutral' | 'amber' | 'red'> = {
  PENDING: 'neutral',
  SYNCING: 'amber',
  OK: 'sage',
  ERROR: 'red',
}

const RUN_LABEL: Record<SyncRunStatus, string> = {
  RUNNING: 'Running',
  SUCCESS: 'Succeeded',
  PARTIAL_FAILURE: 'Finished with rejected rows',
  FAILED: 'Failed',
}

const RUN_TONE: Record<SyncRunStatus, 'sage' | 'neutral' | 'amber' | 'red'> = {
  RUNNING: 'neutral',
  SUCCESS: 'sage',
  PARTIAL_FAILURE: 'amber',
  FAILED: 'red',
}

const ACCOUNT_TYPES: AccountType[] = ['CHECKING', 'SAVINGS', 'CREDIT_CARD', 'INVESTMENT', 'OTHER']

/* ------------------------------------------------------------- error copy */

/**
 * One switch per screen, over exactly the slugs these operations narrow
 * `ErrorResponse.type` to. A 429 is a wait-and-retry state with real copy — not
 * a generic failure, and never a logout: `src/api/client.ts` owns the session.
 */
function accountErrorCopy(error: ApiError): string {
  switch (error.code) {
    case 'not-found':
      return 'That account is not in this household any more. Reload the page.'
    case 'conflict':
      return error.detail ?? 'Another change reached that account first. Reload and try again.'
    case 'validation-error':
      return error.detail ?? 'Some of those details were refused.'
    case 'forbidden':
    case 'csrf-header-missing':
      return 'You do not have permission to do that.'
    case 'rate-limited':
      return 'Too many requests just now. Wait a few seconds, then try again.'
    default:
      return error.isNetworkError
        ? 'Trove could not reach the server. Check your connection and try again.'
        : 'That did not go through.'
  }
}

/* --------------------------------------------------------------- dialogs */

interface DialogShellProps {
  title: string
  onClose: () => void
  children: React.ReactNode
  testId: string
}

/** Every dialog on this page: `QlModal` for the role and Escape, this for focus. */
function DialogShell({ title, onClose, children, testId }: DialogShellProps) {
  const ref = useDialogFocus<HTMLDivElement>(true)
  return (
    <QlModal isOpen onClose={onClose} title={title}>
      <div ref={ref} tabIndex={-1} className={styles.stack} data-testid={testId}>
        {children}
      </div>
    </QlModal>
  )
}

/**
 * The kinds a member can create here, and what choosing one means.
 *
 * `SIMPLEFIN` is deliberately absent: that row is created by the credential
 * exchange ("Connect a bank"), not by this form. The other three are exactly
 * what `AccountRequest` accepts, and `createAccount`'s own description says why
 * the file-fed kinds exist — they are "the row that a later credential exchange
 * or file upload attaches itself to".
 *
 * **This is the fix for the defect that made the import wizard unreachable.**
 * The Import control on a row is offered for `CSV` and `OFX` accounts, and this
 * dialog used to hard-code `sourceKind: 'MANUAL'`, so no member could ever bring
 * such a row into existence: four operations of this step (`createImport`,
 * `getImportPreview`, `commitImport`, `undoImport`) had no reachable entry point
 * at all, and the empty-state copy pointed at a control that never rendered.
 */
const CREATABLE_KINDS = [
  {
    kind: 'MANUAL' as const,
    label: "I'll fill it in myself",
    blurb:
      'The credential-less kind: nothing syncs into it, and it is the only kind you can record a transaction on by hand.',
  },
  {
    kind: 'CSV' as const,
    label: "I'll import CSV statements into it",
    blurb:
      'For a bank that will export a spreadsheet but will not connect. Once it exists, use Import a file on its row; nothing is written until you have seen the preview.',
  },
  {
    kind: 'OFX' as const,
    label: "I'll import OFX or QFX statements into it",
    blurb:
      'For the OFX/QFX download most banks offer. Once it exists, use Import a file on its row; nothing is written until you have seen the preview.',
  },
]

function AddAccountDialog({ onClose }: { onClose: () => void }) {
  const create = useCreateAccountMutation()
  const [sourceKind, setSourceKind] = useState<SourceKind>('MANUAL')
  const [displayName, setDisplayName] = useState('')
  const [institution, setInstitution] = useState('')
  const [accountType, setAccountType] = useState<AccountType>('CHECKING')
  // `''` means "leave it to the server", which uses the member's own
  // `users.currency`. It is not a guessed default written down here.
  const [currency, setCurrency] = useState<CurrencyCode | ''>('')

  const chosen = CREATABLE_KINDS.find((k) => k.kind === sourceKind) ?? CREATABLE_KINDS[0]

  return (
    <DialogShell title="Add an account" onClose={onClose} testId="add-account-dialog">
      <label className={styles.selectLabel} htmlFor="add-account-source">
        How will this account be fed?
      </label>
      <select
        id="add-account-source"
        className={styles.select}
        value={sourceKind}
        onChange={(e) => setSourceKind(e.target.value as SourceKind)}
      >
        {CREATABLE_KINDS.map((k) => (
          <option key={k.kind} value={k.kind}>
            {k.label}
          </option>
        ))}
      </select>
      <p className={styles.lede} data-testid="add-account-blurb">
        {chosen.blurb} How an account is fed cannot be changed afterwards — every transaction
        already stored on it was read that way.
      </p>
      <QlInput
        label="Account name"
        value={displayName}
        onChange={(e) => setDisplayName(e.target.value)}
        error={create.error?.fieldError('displayName')}
      />
      <QlInput
        label="Institution (optional)"
        value={institution}
        onChange={(e) => setInstitution(e.target.value)}
        error={create.error?.fieldError('institution')}
      />
      <label className={styles.selectLabel} htmlFor="add-account-type">
        Account type
      </label>
      <select
        id="add-account-type"
        className={styles.select}
        value={accountType}
        onChange={(e) => setAccountType(e.target.value as AccountType)}
      >
        {ACCOUNT_TYPES.map((t) => (
          <option key={t} value={t}>
            {ACCOUNT_TYPE_LABEL[t]}
          </option>
        ))}
      </select>
      {/* The currency matters most on a file-fed account: a statement whose rows
          carry no currency of their own is read in the ACCOUNT's currency, so an
          American statement filed against a Canadian account would be relabelled
          rather than converted. Left blank, the server uses the member's own. */}
      <label className={styles.selectLabel} htmlFor="add-account-currency">
        Currency it is held in
      </label>
      <select
        id="add-account-currency"
        className={styles.select}
        value={currency}
        onChange={(e) => setCurrency(e.target.value as CurrencyCode | '')}
      >
        <option value="">My reporting currency</option>
        {COUNTRY_PROFILES.map((p) => (
          <option key={p.currency} value={p.currency}>
            {p.currency} · {p.label}
          </option>
        ))}
      </select>
      {create.isError && create.error.fieldError('displayName') === undefined && (
        <p className={styles.error} role="alert">
          {accountErrorCopy(create.error)}
        </p>
      )}
      <div className={styles.dialogActions}>
        <QlButton variant="ghost" onClick={onClose}>
          Cancel
        </QlButton>
        <QlButton
          variant="primary"
          loading={create.isPending}
          disabled={displayName.trim() === ''}
          onClick={() =>
            create.mutate(
              {
                sourceKind,
                displayName: displayName.trim(),
                institution: institution.trim() === '' ? null : institution.trim(),
                accountType,
                // Omitted rather than guessed when the member kept the default.
                ...(currency === '' ? {} : { currency }),
              },
              { onSuccess: onClose }
            )
          }
        >
          Add account
        </QlButton>
      </div>
    </DialogShell>
  )
}

function EditAccountDialog({ account, onClose }: { account: AccountResponse; onClose: () => void }) {
  const update = useUpdateAccountMutation()
  const [displayName, setDisplayName] = useState(account.displayName)
  const [institution, setInstitution] = useState(account.institution ?? '')
  const [accountType, setAccountType] = useState<AccountType>(account.accountType)

  return (
    <DialogShell
      title={`Edit ${account.displayName}`}
      onClose={onClose}
      testId="edit-account-dialog"
    >
      <QlInput
        label="Account name"
        value={displayName}
        onChange={(e) => setDisplayName(e.target.value)}
        error={update.error?.fieldError('displayName')}
      />
      <QlInput
        label="Institution (optional)"
        value={institution}
        onChange={(e) => setInstitution(e.target.value)}
      />
      <label className={styles.selectLabel} htmlFor="edit-account-type">
        Account type
      </label>
      <select
        id="edit-account-type"
        className={styles.select}
        value={accountType}
        onChange={(e) => setAccountType(e.target.value as AccountType)}
      >
        {ACCOUNT_TYPES.map((t) => (
          <option key={t} value={t}>
            {ACCOUNT_TYPE_LABEL[t]}
          </option>
        ))}
      </select>
      {/* Not offered, because the server will not accept either: how the row is
          fed, and the denomination every transaction already on it was written
          in. Changing them through a rename form would silently reinterpret
          money that is already stored. */}
      <p className={styles.hint} data-testid="edit-account-fixed">
        {SOURCE_LABEL[account.sourceKind]} · {account.currency}. How this account is fed and the
        currency it is held in are fixed — every transaction already stored on it is denominated in
        that currency.
      </p>
      {update.isError && update.error.fieldError('displayName') === undefined && (
        <p className={styles.error} role="alert">
          {accountErrorCopy(update.error)}
        </p>
      )}
      <div className={styles.dialogActions}>
        <QlButton variant="ghost" onClick={onClose}>
          Cancel
        </QlButton>
        <QlButton
          variant="primary"
          loading={update.isPending}
          disabled={displayName.trim() === ''}
          onClick={() =>
            update.mutate(
              {
                id: account.id,
                body: {
                  displayName: displayName.trim(),
                  institution: institution.trim() === '' ? null : institution.trim(),
                  accountType,
                },
              },
              { onSuccess: onClose }
            )
          }
        >
          Save changes
        </QlButton>
      </div>
    </DialogShell>
  )
}

function DeleteAccountDialog({
  account,
  onClose,
}: {
  account: AccountResponse
  onClose: () => void
}) {
  const remove = useDeleteAccountMutation()
  return (
    <DialogShell
      title={`Disconnect ${account.displayName}?`}
      onClose={onClose}
      testId="delete-account-dialog"
    >
      {/* The confirmation states what actually happens, because the server
          cascades and it is not recoverable. */}
      <p className={styles.lede} data-testid="delete-consequence">
        This removes the account <strong>and every transaction stored against it</strong>. Those
        transactions disappear from your ledger, your budgets and your reports, and it cannot be
        undone — reconnecting later fetches history again only as far back as the source will go.
      </p>
      {remove.isError && (
        <p className={styles.error} role="alert">
          {accountErrorCopy(remove.error)}
        </p>
      )}
      <div className={styles.dialogActions}>
        <QlButton variant="ghost" onClick={onClose}>
          Keep the account
        </QlButton>
        <QlButton
          variant="danger"
          loading={remove.isPending}
          onClick={() => remove.mutate(account.id, { onSuccess: onClose })}
        >
          Delete the account and its transactions
        </QlButton>
      </div>
    </DialogShell>
  )
}

function SyncHistoryDialog({
  account,
  onClose,
}: {
  account: AccountResponse
  onClose: () => void
}) {
  const logs = useAccountSyncLogs(account.id)
  // The account as it is NOW, not as the list last saw it: a household mate may
  // have synced or renamed it since, and this dialog is precisely where a stale
  // sync status would mislead.
  const fresh = useAccount(account.id)
  const current = fresh.data ?? account
  const format = useUserFormat()

  return (
    <DialogShell
      title={`Sync history — ${account.displayName}`}
      onClose={onClose}
      testId="sync-history-dialog"
    >
      <p className={styles.accountMeta} data-testid="sync-history-status">
        <QlBadge variant={SYNC_TONE[current.syncStatus]}>{SYNC_LABEL[current.syncStatus]}</QlBadge>{' '}
        {current.lastSyncedAt
          ? `Last synced ${format.formatDateTime(current.lastSyncedAt)}`
          : 'Never synced'}
      </p>
      {current.errorMessage && (
        <p className={styles.error} role="alert">
          {current.errorMessage}
        </p>
      )}
      {logs.isPending && (
        <div role="status">
          <QlSkeleton variant="row" rows={3} data-testid="sync-history-skeleton" />
          <span className={styles.srOnly}>Loading sync history</span>
        </div>
      )}
      {logs.isError && (
        <p className={styles.error} role="alert">
          {accountErrorCopy(logs.error)}
        </p>
      )}
      {logs.data && logs.data.length === 0 && (
        <EmptyState title="No sync has run yet">
          <p>Trigger one from the account, or import a file, and the runs appear here.</p>
        </EmptyState>
      )}
      {logs.data && logs.data.length > 0 && (
        <div className={styles.tableWrap}>
          <table className={styles.table} data-testid="sync-history-table">
            <caption className={styles.caption}>The 50 most recent runs, newest first</caption>
            <thead>
              <tr>
                <th scope="col">Started</th>
                <th scope="col">Outcome</th>
                <th scope="col" className={styles.right}>
                  Added
                </th>
                <th scope="col" className={styles.right}>
                  Skipped
                </th>
                <th scope="col" className={styles.right}>
                  Rejected
                </th>
              </tr>
            </thead>
            <tbody>
              {logs.data.map((run: SyncLogResponse) => (
                <tr key={run.id} data-testid={`sync-run-${run.id}`}>
                  <td>{format.formatDateTime(run.startedAt)}</td>
                  <td>
                    <QlBadge variant={RUN_TONE[run.status]}>{RUN_LABEL[run.status]}</QlBadge>
                    {/* A failed run's reason, in words. The status word above is
                        already there, so this is detail rather than the only cue. */}
                    {run.errorMessage && <span className={styles.reason}> {run.errorMessage}</span>}
                  </td>
                  <td className={styles.right}>{run.transactionsNew}</td>
                  <td className={styles.right}>{run.transactionsSkipped}</td>
                  <td className={styles.right}>{run.transactionsFailed}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <div className={styles.dialogActions}>
        <QlButton variant="primary" onClick={onClose}>
          Close
        </QlButton>
      </div>
    </DialogShell>
  )
}

function ConnectBankDialog({ onClose }: { onClose: () => void }) {
  const exchange = useExchangeSetupTokenMutation()
  const [setupToken, setSetupToken] = useState('')

  /**
   * Clearing the component's own state takes the token out of the input and out
   * of the DOM, but a settled mutation keeps its `variables` — the object
   * carrying the raw token — in the shared `MutationCache` for the default
   * garbage-collection window after this dialog unmounts, and that cache is
   * reachable from anywhere in the app (this page reads `undo.variables` a few
   * hundred lines below). `reset()` on unmount drops it. It is deliberately not
   * called in `onSettled`, which would also erase the success message the member
   * is being shown.
   */
  const reset = exchange.reset
  useEffect(() => reset, [reset])

  function connect() {
    exchange.mutate(
      { sourceKind: 'SIMPLEFIN', setupToken: setupToken.trim() },
      {
        // The token is a single-use credential. It is dropped from component
        // state the moment the call settles, whichever way it went, so it is
        // never re-submitted, never re-rendered and never left sitting in a
        // React tree waiting to be serialised into a snapshot.
        onSettled: () => setSetupToken(''),
      }
    )
  }

  return (
    <DialogShell title="Connect a bank" onClose={onClose} testId="connect-bank-dialog">
      <p className={styles.lede}>
        Paste the one-time setup token from your SimpleFIN Bridge account. Trove exchanges it for a
        permanent connection and never shows it again.
      </p>
      <QlInput
        label="Setup token"
        type="password"
        autoComplete="off"
        value={setupToken}
        onChange={(e) => setSetupToken(e.target.value)}
        error={exchange.error?.fieldError('setupToken')}
      />
      {exchange.isSuccess && (
        <p className={styles.success} role="status">
          Connected {exchange.data.accountIds.length} account
          {exchange.data.accountIds.length === 1 ? '' : 's'}, and started{' '}
          {exchange.data.syncLogs.length} initial sync
          {exchange.data.syncLogs.length === 1 ? '' : 's'}.
        </p>
      )}
      {exchange.isError && exchange.error.fieldError('setupToken') === undefined && (
        <p className={styles.error} role="alert">
          {exchangeErrorCopy(exchange.error)}
        </p>
      )}
      <div className={styles.dialogActions}>
        <QlButton variant="ghost" onClick={onClose}>
          {exchange.isSuccess ? 'Done' : 'Cancel'}
        </QlButton>
        <QlButton
          variant="primary"
          loading={exchange.isPending}
          disabled={setupToken.trim() === ''}
          onClick={connect}
        >
          Connect
        </QlButton>
      </div>
    </DialogShell>
  )
}

/* ------------------------------------------------------------- the page */

type Dialog =
  | { kind: 'add' }
  | { kind: 'connect' }
  | { kind: 'edit'; account: AccountResponse }
  | { kind: 'delete'; account: AccountResponse }
  | { kind: 'history'; account: AccountResponse }
  | { kind: 'import'; account: AccountResponse }

interface CommittedBatch {
  id: string
  filename: string | null
  run: SyncLogResponse
  accountName: string
}

export default function Accounts() {
  const accounts = useAccounts()
  const sync = useSyncAccountMutation()
  const undo = useUndoImportMutation()
  const format = useUserFormat()
  const [dialog, setDialog] = useState<Dialog | null>(null)
  const [syncingId, setSyncingId] = useState<string | null>(null)
  /**
   * The batches committed in this session, so `DELETE /v1/imports/{id}` stays
   * reachable once the wizard has closed. **Contract gap:** there is no
   * `listImports` operation, so a batch cannot be re-found after a reload. This
   * is the whole of what the document makes possible, recorded rather than
   * worked around.
   */
  const [committed, setCommitted] = useState<CommittedBatch[]>([])
  const [undone, setUndone] = useState<string[]>([])

  function runSync(account: AccountResponse) {
    setSyncingId(account.id)
    sync.mutate(account.id, { onSettled: () => setSyncingId(null) })
  }

  return (
    <div className={styles.page}>
      <header className={styles.header}>
        <div>
          <h1 className={styles.heading}>Accounts</h1>
          <p className={styles.lede}>
            Every account this household has. Each one is held in its own currency and reported on
            its own — Trove never converts between currencies.
          </p>
        </div>
        <div className={styles.headerActions}>
          <QlButton variant="ghost" onClick={() => setDialog({ kind: 'add' })}>
            Add an account
          </QlButton>
          <QlButton variant="primary" onClick={() => setDialog({ kind: 'connect' })}>
            Connect a bank
          </QlButton>
        </div>
      </header>

      {sync.isError && (
        <p className={styles.error} role="alert" data-testid="sync-error">
          {accountErrorCopy(sync.error)}
        </p>
      )}

      {accounts.isPending && (
        <div role="status">
          <QlSkeleton variant="row" rows={3} data-testid="accounts-skeleton" />
          <span className={styles.srOnly}>Loading your accounts</span>
        </div>
      )}

      {accounts.isError && (
        <QlCard>
          <div className={styles.stack} data-testid="accounts-error">
            <p className={styles.error} role="alert">
              {accountErrorCopy(accounts.error)}
            </p>
            {accounts.error.status === 429 && (
              <p className={styles.hint}>
                Trove is asking you to slow down, not refusing you — your session is fine.
              </p>
            )}
            <div>
              <QlButton variant="primary" onClick={() => void accounts.refetch()}>
                Try again
              </QlButton>
            </div>
          </div>
        </QlCard>
      )}

      {accounts.data && accounts.data.length === 0 && (
        <QlCard>
          <EmptyState
            title="No accounts yet"
            data-testid="accounts-empty"
            actions={
              <>
                <QlButton variant="primary" onClick={() => setDialog({ kind: 'connect' })}>
                  Connect a bank
                </QlButton>
                <QlButton variant="ghost" onClick={() => setDialog({ kind: 'add' })}>
                  Add an account
                </QlButton>
              </>
            }
          >
            <p>
              Connect a bank with a SimpleFIN token, add an account you will fill in yourself, or
              add one you will import CSV or OFX statements into — the Import control appears on a
              file-fed account&rsquo;s own row.
            </p>
          </EmptyState>
        </QlCard>
      )}

      {accounts.data && accounts.data.length > 0 && (
        <ul className={styles.accountList} data-testid="accounts-list">
          {accounts.data.map((account) => {
            const busy = account.syncStatus === 'SYNCING' || syncingId === account.id
            const canImport = account.sourceKind === 'CSV' || account.sourceKind === 'OFX'
            // Only a credential-backed account has anything to sync FROM. A
            // file-fed one is fed by Import, and `syncAccount`'s own description
            // says it answers 409 until a source is registered for that kind —
            // so offering the control there would be a button whose only
            // possible outcome is a refusal.
            const canSync = account.sourceKind === 'SIMPLEFIN'
            return (
              <li key={account.id}>
                <QlCard>
                  <div className={styles.accountRow} data-testid={`account-${account.id}`}>
                    <div className={styles.accountMain}>
                      <h2 className={styles.accountName}>{account.displayName}</h2>
                      <p className={styles.accountMeta}>
                        {account.institution ?? 'No institution recorded'} ·{' '}
                        {ACCOUNT_TYPE_LABEL[account.accountType]} · {account.currency} ·{' '}
                        {SOURCE_LABEL[account.sourceKind]}
                      </p>
                      <p className={styles.accountMeta}>
                        <QlBadge variant={SYNC_TONE[account.syncStatus]}>
                          {SYNC_LABEL[account.syncStatus]}
                        </QlBadge>{' '}
                        {account.lastSyncedAt
                          ? `Last synced ${format.formatDateTime(account.lastSyncedAt)}`
                          : 'Never synced'}
                      </p>
                      {account.errorMessage && (
                        <p className={styles.error} role="alert">
                          {account.errorMessage}
                        </p>
                      )}
                    </div>
                    <div className={styles.accountActions}>
                      {canSync && (
                        <QlButton
                          variant="ghost"
                          loading={busy}
                          disabled={busy}
                          onClick={() => runSync(account)}
                        >
                          {busy ? 'Syncing…' : `Sync ${account.displayName}`}
                        </QlButton>
                      )}
                      {canImport && (
                        <QlButton
                          variant="ghost"
                          onClick={() => setDialog({ kind: 'import', account })}
                        >
                          Import a file into {account.displayName}
                        </QlButton>
                      )}
                      <QlButton
                        variant="ghost"
                        onClick={() => setDialog({ kind: 'history', account })}
                      >
                        Sync history for {account.displayName}
                      </QlButton>
                      <QlButton variant="ghost" onClick={() => setDialog({ kind: 'edit', account })}>
                        Edit {account.displayName}
                      </QlButton>
                      <QlButton
                        variant="danger"
                        onClick={() => setDialog({ kind: 'delete', account })}
                      >
                        Delete {account.displayName}
                      </QlButton>
                    </div>
                  </div>
                </QlCard>
              </li>
            )
          })}
        </ul>
      )}

      {/* Undo lives HERE rather than only inside the wizard: a member who closes
          the modal after committing must still be able to take the rows back
          out, and the wizard is gone by then. */}
      {committed.length > 0 && (
        <QlCard>
          <div className={styles.stack} data-testid="committed-imports">
            <h2 className={styles.sectionTitle}>Imports from this session</h2>
            {undo.isError && (
              <p className={styles.error} role="alert">
                {accountErrorCopy(undo.error)}
              </p>
            )}
            <ul className={styles.batchList}>
              {committed.map((batch) => (
                <li key={batch.id} className={styles.batchRow}>
                  <span>
                    {batch.filename ?? 'Statement'} → {batch.accountName}:{' '}
                    {batch.run.transactionsNew} added, {batch.run.transactionsSkipped} skipped
                    {batch.run.transactionsFailed > 0
                      ? `, ${batch.run.transactionsFailed} rejected`
                      : ''}
                  </span>
                  {undone.includes(batch.id) ? (
                    <span className={styles.hint} role="status">
                      Undone
                    </span>
                  ) : (
                    <QlButton
                      variant="ghost"
                      loading={undo.isPending && undo.variables === batch.id}
                      onClick={() =>
                        undo.mutate(batch.id, {
                          onSuccess: () => setUndone((prev) => [...prev, batch.id]),
                        })
                      }
                    >
                      Undo this import
                    </QlButton>
                  )}
                </li>
              ))}
            </ul>
            <p className={styles.hint}>
              Undo removes the rows this import added and that nobody has edited since. A row you
              have already changed stays where it is.
            </p>
          </div>
        </QlCard>
      )}

      {dialog?.kind === 'add' && <AddAccountDialog onClose={() => setDialog(null)} />}
      {dialog?.kind === 'connect' && <ConnectBankDialog onClose={() => setDialog(null)} />}
      {dialog?.kind === 'edit' && (
        <EditAccountDialog account={dialog.account} onClose={() => setDialog(null)} />
      )}
      {dialog?.kind === 'delete' && (
        <DeleteAccountDialog account={dialog.account} onClose={() => setDialog(null)} />
      )}
      {dialog?.kind === 'history' && (
        <SyncHistoryDialog account={dialog.account} onClose={() => setDialog(null)} />
      )}
      {dialog?.kind === 'import' && (
        <ImportWizard
          account={dialog.account}
          onClose={() => setDialog(null)}
          onCommitted={(batch) =>
            setCommitted((prev) => [{ ...batch, accountName: dialog.account.displayName }, ...prev])
          }
        />
      )}
    </div>
  )
}
