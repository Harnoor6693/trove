/**
 * Your data — the full export, and deleting your own account
 * (§4.2, readiness gap P2-16, step 205). Both are step-up operations.
 *
 * ## Step-up is a security mechanism, not a confirmation dialog
 *
 * `POST /v1/me/export` and `POST /v1/me/delete` take a `StepUpRequest`: the
 * caller's current password and, where a second factor is enrolled, a current
 * code. Neither field is required by the schema, because which applies depends
 * on the account — a member who signs in only through an identity provider has
 * no password and proves the code alone, and a member with neither is refused
 * 403 with the server's own explanation (ask an administrator for a reset link).
 * So this screen asks for what the account HAS, and sends only that.
 *
 * The body goes through `useCredentialMutation`, so it is never a mutation
 * variable, never in the query cache, never in component state after the call
 * and never in a snapshot. A wrong password or code is 403 `step-up-required`
 * and is shown as "that password or code was not accepted", never as a generic
 * failure.
 *
 * ## The export
 *
 * `createDataExport` answers **202** with a `PENDING` row and hands the work to
 * a background executor; the list below polls itself to `COMPLETE`. There is a
 * **one-run-per-member-per-day cap enforced server-side as a 409**, whose own
 * message says when the member may ask again — so this screen shows that message
 * rather than hiding the button behind a timer it invented.
 *
 * **The download is authenticated and is not a link.** It answers
 * `application/zip` behind the session with `Content-Disposition: attachment`;
 * nothing is presigned and no storage URL is ever handed out. It goes through
 * `fetchAuthenticatedFile` / `saveAuthenticatedFile` (`@api/binary`, landed by
 * step 204 and already reused by family C), which revokes the object URL. A
 * download of a run that is not complete — or one whose 24-hour link has expired
 * — is 409, read as "still generating" / the server's own words, never a failure.
 *
 * **Receipts are metadata only.** From `createDataExport`'s own description:
 * *"Ids, status, source, merchant, amounts, the tax split, the matched
 * transaction, the uploader, timestamps and the image file names — never the
 * image bytes, never a storage key and never a URL. An archive that inlined
 * every photograph a household ever took would not fit in the memory this export
 * is allowed. A client should say so."* It says so.
 *
 * ## The deletion
 *
 * A **soft** delete with a seven-day grace an administrator can restore from,
 * then a purge. The screen states, before the step-up and in the member's own
 * words, what the operation's description states: the account is disabled
 * immediately and every session ends; an admin can restore it inside the grace;
 * after that it is purged for good; the household's shared ledger SURVIVES with
 * their authorship removed; and what goes with them. There is no countdown and
 * no self-restore affordance, because restore is the ADMIN's route. The
 * household's last `ADMIN` is refused 409 and that refusal is rendered as an
 * explanation with a way forward, not as an error.
 *
 * It calls `POST /v1/me/delete`, not `DELETE /v1/me`. They are the same
 * operation; the spec says of the POST twin, "**This is the one that always
 * works**" — a DELETE body is legal but RFC 9110 leaves it undefined and
 * intermediaries drop it silently, which would turn an irreversible decision
 * into an unexplained 403.
 */
import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useQueryClient } from '@tanstack/react-query'
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
import { useCreateDataExportMutation, useDataExports, useDeleteMyAccountMutation } from '@hooks/useDataExport'
import { downloadDataExport } from '@api/dataexport'
import { saveAuthenticatedFile } from '@api/binary'
import { toApiError, type ApiError } from '@api/client'
import { useAuthStore } from '@stores/authStore'
import { dataExportErrorCopy, deleteAccountErrorCopy } from './accountErrorCopy'
import type { DataExportRunResponse, StepUpRequest } from '@api/types'
import styles from './Settings.module.css'

const STATUS_BADGE: Record<
  DataExportRunResponse['status'],
  { variant: 'sage' | 'amber' | 'red' | 'neutral'; label: string }
> = {
  PENDING: { variant: 'neutral', label: 'Waiting to start' },
  GENERATING: { variant: 'amber', label: 'Generating' },
  COMPLETE: { variant: 'sage', label: 'Ready' },
  FAILED: { variant: 'red', label: 'Failed' },
}

/* ----------------------------------------------------------- step-up form */

/**
 * The step-up fields, shared by the export and the deletion.
 *
 * Which fields exist is decided by what the ACCOUNT has, not by what the screen
 * would like to ask for: `totpEnabled` comes from the server's own `me`
 * response. The values live in the calling dialog's state only for as long as
 * the form is open, and that dialog clears them the moment the call settles.
 */
function StepUpFields({
  password,
  code,
  totpEnabled,
  onPassword,
  onCode,
}: {
  password: string
  code: string
  totpEnabled: boolean
  onPassword: (v: string) => void
  onCode: (v: string) => void
}) {
  return (
    <>
      <QlInput
        label="Your password"
        type="password"
        autoComplete="current-password"
        value={password}
        onChange={(e) => onPassword(e.target.value)}
        hint="Leave this empty if you only ever sign in through your identity provider."
      />
      {totpEnabled && (
        <QlInput
          label="Code from your authenticator"
          inputMode="numeric"
          autoComplete="one-time-code"
          required
          value={code}
          onChange={(e) => onCode(e.target.value)}
          hint="A recovery code works here too."
        />
      )}
    </>
  )
}

/** Builds the body, omitting a field the member did not fill rather than sending `''`. */
function stepUpBody(password: string, code: string, totpEnabled: boolean): StepUpRequest {
  const body: StepUpRequest = {}
  if (password) body.password = password
  if (totpEnabled && code) body.code = code
  return body
}

/* ------------------------------------------------------------- export */

function StartExportDialog({ onClose }: { onClose: () => void }) {
  const create = useCreateDataExportMutation()
  const totpEnabled = useAuthStore((s) => s.user?.totpEnabled ?? false)
  const ref = useDialogFocus<HTMLDivElement>(true)
  const [password, setPassword] = useState('')
  const [code, setCode] = useState('')

  function submit(e: React.FormEvent) {
    e.preventDefault()
    create.submit(stepUpBody(password, code, totpEnabled), {
      onSuccess: () => {
        setPassword('')
        setCode('')
        onClose()
      },
      onError: () => {
        // Cleared on refusal too: a rejected credential must not sit in this
        // component waiting for the member to close the dialog.
        setPassword('')
        setCode('')
      },
    })
  }

  return (
    <QlModal isOpen onClose={onClose} title="Export everything">
      <div ref={ref} tabIndex={-1} className={styles.stack} data-testid="start-export-dialog">
        <p className={styles.note}>
          Trove builds a ZIP archive of your household's ledger — transactions as CSV, and one JSON
          file each for accounts, categories, merchant rules, budget periods, envelopes, savings
          goals, subscriptions, net-worth snapshots, assets, liabilities, receipts, coach
          conversations and notification preferences — plus a manifest. The ledger is shared, so it
          contains your household mates' rows too; only you can download this archive.
        </p>
        <p className={styles.note}>
          <strong>Receipts are included as details only, never as images.</strong> Merchant,
          amounts, the tax split, the matched transaction and the file names are there; the
          photographs are not, because an archive holding every image a household ever took would
          not fit. Download those individually from the Receipts screen.
        </p>
        <p className={styles.note}>
          Every amount is written exactly as it is stored, with its own currency beside it. Nothing
          is converted and no total is blended across currencies.
        </p>
        <p className={styles.note}>You can start one export a day.</p>
        <form className={styles.stack} onSubmit={submit}>
          <StepUpFields
            password={password}
            code={code}
            totpEnabled={totpEnabled}
            onPassword={setPassword}
            onCode={setCode}
          />
          {create.isError && (
            <p className={styles.error} role="alert" data-testid="export-error">
              {dataExportErrorCopy(create.error)}
            </p>
          )}
          <div className={styles.dialogActions}>
            <QlButton type="button" variant="ghost" onClick={onClose}>
              Cancel
            </QlButton>
            <QlButton type="submit" variant="primary" loading={create.isPending}>
              Start export
            </QlButton>
          </div>
        </form>
      </div>
    </QlModal>
  )
}

interface DownloadState {
  runId: string | null
  notReady: boolean
  error: ApiError | null
}

function ExportRuns() {
  const [page, setPage] = useState(0)
  const runs = useDataExports({ page, limit: 20 })
  const format = useUserFormat()
  const [download, setDownload] = useState<DownloadState>({
    runId: null,
    notReady: false,
    error: null,
  })

  async function handleDownload(run: DataExportRunResponse) {
    setDownload({ runId: run.id, notReady: false, error: null })
    try {
      const file = await downloadDataExport(run.id)
      // Hands the bytes over and revokes the object URL — a document-lifetime
      // reference to a decoded archive is not something to keep "just in case".
      saveAuthenticatedFile(file, 'trove-export.zip')
      setDownload({ runId: null, notReady: false, error: null })
    } catch (err) {
      const apiErr = toApiError(err)
      // 409 is BOTH "not finished" and "the 24-hour link has expired". The
      // server's own detail distinguishes them; this screen shows it verbatim
      // rather than guessing which happened.
      if (apiErr.code === 'conflict') {
        setDownload({ runId: run.id, notReady: true, error: apiErr })
      } else {
        setDownload({ runId: run.id, notReady: false, error: apiErr })
      }
    }
  }

  const rows = runs.data?.rows ?? []
  const meta = runs.data?.meta

  const columns: QlTableColumn<DataExportRunResponse & Record<string, unknown>>[] = [
    {
      key: 'status',
      header: 'Status',
      render: (row) => (
        <QlBadge variant={STATUS_BADGE[row.status].variant}>
          {STATUS_BADGE[row.status].label}
        </QlBadge>
      ),
    },
    { key: 'createdAt', header: 'Started', render: (row) => format.formatDateTime(row.createdAt) },
    {
      key: 'expiresAt',
      header: 'Link expires',
      render: (row) => (row.expiresAt ? format.formatDateTime(row.expiresAt) : '—'),
    },
    {
      key: 'errorMessage',
      header: 'Detail',
      render: (row) =>
        row.status === 'FAILED' ? (
          <span className={styles.error} role="alert">
            {row.errorMessage ?? 'This export could not be generated.'}
          </span>
        ) : null,
    },
    {
      key: 'actions',
      header: 'Actions',
      render: (row) =>
        row.status === 'COMPLETE' ? (
          <QlButton
            size="sm"
            variant="ghost"
            loading={download.runId === row.id && !download.notReady && !download.error}
            onClick={() => void handleDownload(row)}
          >
            Download
          </QlButton>
        ) : null,
    },
  ]

  return (
    <div className={styles.stack}>
      {runs.isPending && <QlSkeleton data-testid="exports-skeleton" />}
      {runs.isError && (
        <p className={styles.error} role="alert">
          {dataExportErrorCopy(runs.error)}
        </p>
      )}
      {runs.data && rows.length === 0 && (
        <EmptyState title="No exports yet">
          <p>Start one to get a copy of everything in your household ledger.</p>
        </EmptyState>
      )}
      {runs.data && rows.length > 0 && (
        <div className={styles.tableWrap} data-testid="exports-table">
          <QlTable
            columns={columns}
            rows={rows as (DataExportRunResponse & Record<string, unknown>)[]}
          />
        </div>
      )}
      {download.notReady && (
        <p className={styles.note} role="status" data-testid="download-not-ready">
          {download.error
            ? dataExportErrorCopy(download.error)
            : 'That export is still generating. Try again once it shows "Ready".'}
        </p>
      )}
      {download.error && !download.notReady && (
        <p className={styles.error} role="alert" data-testid="download-error">
          {dataExportErrorCopy(download.error)}
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
          <span className={styles.note}>
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

/* ---------------------------------------------------------- self-deletion */

function DeleteAccountDialog({ onClose }: { onClose: () => void }) {
  const remove = useDeleteMyAccountMutation()
  const totpEnabled = useAuthStore((s) => s.user?.totpEnabled ?? false)
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const ref = useDialogFocus<HTMLDivElement>(true)
  const [password, setPassword] = useState('')
  const [code, setCode] = useState('')

  function submit(e: React.FormEvent) {
    e.preventDefault()
    remove.submit(stepUpBody(password, code, totpEnabled), {
      onSuccess: () => {
        setPassword('')
        setCode('')
        // Every server-side session of this account was revoked the instant the
        // call landed, so the only honest local state is "signed out".
        useAuthStore.setState({ status: 'anonymous', user: null, hydrationError: null })
        // The same wipe `useLogout` does, and for the same reason: the query
        // client is one module-level singleton and `/login` is a public route in
        // this very SPA instance, so without this the deleted account's
        // transactions, accounts, receipts and security events stay live in
        // memory for whoever signs in on this tab next. `useLogout` itself is
        // not reused here — it would POST /v1/auth/logout against a session the
        // server has already revoked — but its cache invariant still holds.
        navigate('/login', { replace: true })
        queryClient.clear()
      },
      onError: () => {
        setPassword('')
        setCode('')
      },
    })
  }

  return (
    <QlModal isOpen onClose={onClose} title="Delete your account?">
      <div ref={ref} tabIndex={-1} className={styles.stack} data-testid="delete-account-dialog">
        <p className={styles.warning}>
          Your account is disabled the moment you confirm, and every session you have anywhere ends
          at once. An administrator of your household can bring it back for seven days. After that
          it is erased permanently and nobody can restore it.
        </p>
        <p className={styles.note}>Erased with you:</p>
        <ul className={styles.consequences}>
          <li>your password, two-factor secret and any identity-provider links</li>
          <li>your sessions, reset links, AI settings and notification preferences and inbox</li>
          <li>your webhook subscriptions, coach conversations and insights</li>
          <li>your tax-line overrides, and every export run of yours with its file</li>
        </ul>
        <p className={styles.note}>Kept by the household:</p>
        <ul className={styles.consequences}>
          <li>
            the shared ledger — transactions, accounts, categories, rules, budgets, envelopes,
            goals, net-worth snapshots, assets, liabilities, subscriptions and receipts, images
            included — with your name removed from the rows that recorded who entered them
          </li>
          <li>
            the security-event history, with your user id removed: an incident review must not be
            erasable by deleting an account
          </li>
        </ul>
        <p className={styles.note}>
          Your email address stays reserved until the erasure runs, so it cannot be registered or
          invited again in the meantime. If you mean to come back with the same address, ask an
          administrator to restore your account instead.
        </p>
        <form className={styles.stack} onSubmit={submit}>
          <StepUpFields
            password={password}
            code={code}
            totpEnabled={totpEnabled}
            onPassword={setPassword}
            onCode={setCode}
          />
          {remove.isError && (
            <p
              className={remove.error.code === 'conflict' ? styles.note : styles.error}
              role={remove.error.code === 'conflict' ? 'status' : 'alert'}
              data-testid="delete-account-error"
            >
              {deleteAccountErrorCopy(remove.error)}
            </p>
          )}
          <div className={styles.dialogActions}>
            {/* The safe action is the primary one; the destructive action is not
                the default and is labelled with what it does, not with "OK". */}
            <QlButton type="button" variant="primary" onClick={onClose}>
              Keep my account
            </QlButton>
            <QlButton type="submit" variant="danger" loading={remove.isPending}>
              Delete my account
            </QlButton>
          </div>
        </form>
      </div>
    </QlModal>
  )
}

/* ------------------------------------------------------------- section */

export function DataSection() {
  const [dialog, setDialog] = useState<'export' | 'delete' | null>(null)

  return (
    <QlCard>
      <div className={styles.section} id="your-data">
        <div className={styles.header}>
          <h2 className={styles.sectionTitle}>Your data</h2>
          <QlButton size="sm" variant="primary" onClick={() => setDialog('export')}>
            Export everything
          </QlButton>
        </div>
        <p className={styles.note}>
          Exports are built in the background and stay downloadable for 24 hours. Trove asks for
          your password — and a code if you use two-factor — because an archive is a copy of your
          whole financial life, and holding a signed-in browser is not proof of who is at the
          keyboard.
        </p>

        <ExportRuns />

        <div className={styles.header}>
          <h3 className={styles.sectionTitle}>Delete your account</h3>
          <QlButton size="sm" variant="ghost" onClick={() => setDialog('delete')}>
            Delete my account…
          </QlButton>
        </div>
        <p className={styles.note}>
          Your account is disabled straight away and permanently erased after seven days. Your
          household keeps its shared ledger.
        </p>

        {dialog === 'export' && <StartExportDialog onClose={() => setDialog(null)} />}
        {dialog === 'delete' && <DeleteAccountDialog onClose={() => setDialog(null)} />}
      </div>
    </QlCard>
  )
}
