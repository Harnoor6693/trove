/**
 * `ImportWizard` — the CSV / OFX upload → preview → commit flow, as a modal on
 * the Accounts page.
 *
 * **Why a modal and not a route.** Importing is an action on ONE account, not a
 * destination: it starts from an account row, it writes to that account, and it
 * is over in three steps. The nav already carries ten entries and an eleventh
 * would push a real destination behind "More" on a phone. A wizard behind a URL
 * the member can reload mid-flow is also a worse experience than one they
 * cannot — a reloaded upload step has lost the file, and a reloaded preview
 * step would have to re-fetch a batch the member has no way to name. So the
 * whole flow lives here, and the one thing that must outlive it — the committed
 * batch, so undo stays reachable — is handed back to the page via `onCommitted`.
 *
 * **Nothing is written until the member says so.** `POST /v1/imports` uploads and
 * PREVIEWS; it does not insert one transaction row. The preview says exactly what
 * committing would do — how many rows are new, how many are duplicates that will
 * be skipped, how many are rejected and why — with every amount rendered as money
 * in the file's own currency. `POST /v1/imports/{id}/commit` is the only call
 * that writes, and it carries the member's corrections to the detected mapping,
 * which is why there is no separate "update the mapping" route.
 */
import { useState } from 'react'
import { QlButton } from '@components/ui/QlButton/QlButton'
import { QlInput } from '@components/ui/QlInput/QlInput'
import { QlModal } from '@components/ui/QlModal/QlModal'
import { QlBadge } from '@components/ui/QlBadge/QlBadge'
import { MoneyDisplay } from '@components/MoneyDisplay/MoneyDisplay'
import { useUserFormat } from '@hooks/useUserFormat'
import { useDialogFocus } from '@hooks/useDialogFocus'
import {
  useCommitImportMutation,
  useCreateImportMutation,
} from '@hooks/useImports'
import type { ApiError } from '@api/client'
import type {
  AccountResponse,
  CsvColumnMappingRequest,
  ImportPreviewResponse,
  ImportRowVerdict,
  ImportSourceKind,
  SyncLogResponse,
} from '@api/types'
import styles from './Accounts.module.css'

/** The verdicts the contract declares, each with the word that explains it. */
const VERDICT_LABEL: Record<ImportRowVerdict, string> = {
  NEW: 'Will be added',
  DUPLICATE: 'Already in Trove — skipped',
  FUZZY_DUPLICATE: 'Looks like a row you already have — skipped',
  INVALID: 'Cannot be read — rejected',
}

const VERDICT_TONE: Record<ImportRowVerdict, 'sage' | 'neutral' | 'amber' | 'red'> = {
  NEW: 'sage',
  DUPLICATE: 'neutral',
  FUZZY_DUPLICATE: 'amber',
  INVALID: 'red',
}

/** Copy for exactly the slugs `POST /v1/imports` narrows `ErrorResponse.type` to. */
function uploadErrorCopy(error: ApiError): string {
  switch (error.code) {
    case 'upload-too-large':
      return (
        error.detail ??
        'That file is larger than this server will store. Try a shorter date range, or commit an import you have already previewed to free the allowance.'
      )
    case 'validation-error':
      return (
        error.fieldError('accountId') ??
        error.fieldError('file') ??
        error.detail ??
        'That file could not be read as the format it claims to be.'
      )
    case 'conflict':
      return 'This account already has an import in progress.'
    case 'not-found':
      return 'That account is no longer here. Reload the page.'
    case 'rate-limited':
      return 'Too many uploads just now. Wait a few seconds and try again.'
    case 'forbidden':
    case 'csrf-header-missing':
      return 'Your session could not be verified. Reload the page and try again.'
    default:
      return 'The upload did not go through. Nothing was imported.'
  }
}

/** Copy for exactly the slugs `POST /v1/imports/{id}/commit` narrows to. */
function commitErrorCopy(error: ApiError): string {
  switch (error.code) {
    case 'conflict':
      return error.detail ?? 'This import has already been committed or undone.'
    case 'validation-error':
      return error.fieldError('mapping') ?? error.detail ?? 'That column mapping was refused.'
    case 'not-found':
      return 'That import is no longer here.'
    case 'rate-limited':
      return 'Too many requests just now. Wait a few seconds and try again.'
    default:
      return 'The import could not be committed. Nothing was written.'
  }
}

function importKindFor(account: AccountResponse): ImportSourceKind | undefined {
  if (account.sourceKind === 'CSV' || account.sourceKind === 'OFX') return account.sourceKind
  return undefined
}

export interface ImportWizardProps {
  account: AccountResponse
  onClose: () => void
  /** Handed the committed batch so undo stays reachable once this modal is gone. */
  onCommitted: (batch: { id: string; filename: string | null; run: SyncLogResponse }) => void
}

/**
 * The columns of `CsvColumnMappingRequest` a member can correct here, in the
 * order they read on a statement. The sniffer's guess is a guess: it has been
 * wrong about which column is the amount, and until this existed the only
 * recourse was to cancel and edit the file outside Trove.
 *
 * `balanceColumn` is left out on purpose — it names a column that is recognised
 * so it is NOT imported, and offering it as an editable field invites a member
 * to point it at their amounts.
 */
const COLUMN_FIELDS = [
  { key: 'dateColumn' as const, label: 'Date column' },
  { key: 'descriptionColumn' as const, label: 'Description column' },
  { key: 'amountColumn' as const, label: 'Amount column (one signed column)' },
  { key: 'debitColumn' as const, label: 'Money-out column (two-column files)' },
  { key: 'creditColumn' as const, label: 'Money-in column (two-column files)' },
  { key: 'currencyColumn' as const, label: 'Currency column (optional)' },
]

type ColumnKey = (typeof COLUMN_FIELDS)[number]['key']

export function ImportWizard({ account, onClose, onCommitted }: ImportWizardProps) {
  const [file, setFile] = useState<File | null>(null)
  const [preview, setPreview] = useState<ImportPreviewResponse | null>(null)
  const [dateFormat, setDateFormat] = useState<string | null>(null)
  /** Only the columns the member actually retyped; everything else keeps its detected value. */
  const [columns, setColumns] = useState<Partial<Record<ColumnKey, string>>>({})
  /** `null` until the member disagrees with the sniffer about the header row. */
  const [hasHeader, setHasHeader] = useState<boolean | null>(null)
  const [committed, setCommitted] = useState<SyncLogResponse | null>(null)

  const upload = useCreateImportMutation()
  const commit = useCommitImportMutation()
  const format = useUserFormat()
  const dialogRef = useDialogFocus<HTMLDivElement>(true)

  const step = committed ? 3 : preview ? 2 : 1
  const candidates = preview?.dateFormatCandidates ?? []
  const mustChooseFormat = candidates.length > 0 && dateFormat === null

  function doUpload() {
    if (!file) return
    upload.mutate(
      { file, accountId: account.id, sourceKind: importKindFor(account) },
      {
        onSuccess: (batch) => {
          setPreview(batch)
          // A new file is a new mapping: the previous file's corrections must
          // not survive into it.
          setColumns({})
          setHasHeader(null)
          // A preview that already carries a chosen format needs no question
          // asked; one that does not is genuinely ambiguous and the member has
          // to decide. Derived here, where the preview arrives, rather than in
          // an effect reacting to it — the effect was a second render for a
          // value that is known at the moment of the first.
          setDateFormat(batch.mapping?.dateFormat ?? null)
        },
      }
    )
  }

  /**
   * The member's corrections, and nothing else. Every field of
   * `CsvColumnMappingRequest` is optional and an omitted one keeps its detected
   * value, so an untouched mapping produces no body at all — and an empty string
   * is not an omission: the contract reads it as clearing that column, which is
   * exactly what a member emptying the box means.
   */
  function mappingCorrections(): CsvColumnMappingRequest | undefined {
    if (!preview) return undefined
    const detected = preview.mapping
    const out: CsvColumnMappingRequest = {}
    for (const { key } of COLUMN_FIELDS) {
      const typed = columns[key]
      if (typed === undefined) continue
      if (typed === (detected?.[key] ?? '')) continue
      out[key] = typed
    }
    if (hasHeader !== null && hasHeader !== detected?.hasHeader) out.hasHeader = hasHeader
    if (dateFormat && dateFormat !== detected?.dateFormat) out.dateFormat = dateFormat
    return Object.keys(out).length === 0 ? undefined : out
  }

  function doCommit() {
    if (!preview) return
    const mapping = mappingCorrections()
    const body = mapping ? { mapping } : undefined
    commit.mutate(
      { id: preview.id, body },
      {
        onSuccess: (run) => {
          setCommitted(run)
          onCommitted({ id: preview.id, filename: preview.originalFilename, run })
        },
      }
    )
  }

  return (
    <QlModal isOpen onClose={onClose} size="lg" title={`Import into ${account.displayName}`}>
      <div ref={dialogRef} tabIndex={-1} className={styles.wizard} data-testid="import-wizard">
        <p className={styles.stepCounter}>Step {step} of 3</p>

        {/* ------------------------------------------------------- step 1 */}
        {step === 1 && (
          <div className={styles.stack}>
            <p className={styles.lede}>
              Upload a statement exported from your bank. Nothing is written to your ledger until
              you have seen what it would do and said yes.
            </p>
            <label className={styles.fileLabel} htmlFor="import-file">
              Statement file (CSV, OFX or QFX)
            </label>
            <input
              id="import-file"
              data-testid="import-file"
              className={styles.fileInput}
              type="file"
              accept=".csv,.ofx,.qfx,text/csv,application/x-ofx,application/x-qfx"
              onChange={(e) => setFile(e.target.files?.[0] ?? null)}
            />
            {upload.isError && (
              <p className={styles.error} role="alert">
                {uploadErrorCopy(upload.error)}
              </p>
            )}
            <div className={styles.dialogActions}>
              <QlButton variant="ghost" onClick={onClose}>
                Cancel
              </QlButton>
              <QlButton
                variant="primary"
                loading={upload.isPending}
                disabled={file === null}
                onClick={doUpload}
              >
                Preview import
              </QlButton>
            </div>
          </div>
        )}

        {/* ------------------------------------------------------- step 2 */}
        {step === 2 && preview && (
          <div className={styles.stack}>
            <p className={styles.lede}>
              This is what committing <strong>{preview.originalFilename ?? 'this file'}</strong>{' '}
              would do. Nothing has been written yet.
            </p>

            <ul className={styles.countGrid} data-testid="import-counts">
              <li>
                <span className={styles.countValue} data-testid="import-count-new">
                  {preview.newCount}
                </span>
                <span className={styles.countLabel}>will be added</span>
              </li>
              <li>
                <span className={styles.countValue} data-testid="import-count-duplicate">
                  {preview.duplicateCount}
                </span>
                <span className={styles.countLabel}>already in Trove, skipped</span>
              </li>
              <li>
                <span className={styles.countValue} data-testid="import-count-fuzzy">
                  {preview.fuzzyDuplicateCount}
                </span>
                <span className={styles.countLabel}>look like rows you have, skipped</span>
              </li>
              <li>
                <span className={styles.countValue} data-testid="import-count-invalid">
                  {preview.invalidCount}
                </span>
                <span className={styles.countLabel}>rejected</span>
              </li>
            </ul>

            {preview.warnings.length > 0 && (
              <ul className={styles.warnings} role="status">
                {preview.warnings.map((w) => (
                  <li key={w}>{w}</li>
                ))}
              </ul>
            )}

            {candidates.length > 0 && (
              <fieldset className={styles.fieldset} data-testid="import-date-format">
                <legend className={styles.legend}>
                  Which way round are these dates? Trove will not guess between them.
                </legend>
                {candidates.map((c) => (
                  <label key={c.format} className={styles.radioRow}>
                    <input
                      type="radio"
                      name="dateFormat"
                      value={c.format}
                      checked={dateFormat === c.format}
                      onChange={() => setDateFormat(c.format)}
                    />
                    <span>
                      <code>{c.format}</code> — {c.sampleValues.join(', ')} would mean{' '}
                      {c.interpretedDates.map((d) => format.formatDate(d)).join(', ')}
                    </span>
                  </label>
                ))}
              </fieldset>
            )}

            {/* The sniffer's guess, and the member's chance to correct it.
                `commitImport` is the only route that takes a mapping, so this is
                where a wrong column is put right — without it, a file whose
                amount column was misread could only be fixed outside Trove.
                Every box holds the header text exactly as it appears in the
                file (or, for a file with no header row, the column's position
                written as a number). */}
            {preview.mapping && (
              <fieldset className={styles.fieldset} data-testid="import-mapping">
                <legend className={styles.legend}>The columns Trove read, and how to fix one</legend>
                <p className={styles.hint}>
                  A file states its amounts either in one signed column or in a money-out and
                  money-in pair — never both. Naming one clears the other.
                </p>
                {COLUMN_FIELDS.map(({ key, label }) => (
                  <QlInput
                    key={key}
                    label={label}
                    value={columns[key] ?? preview.mapping?.[key] ?? ''}
                    onChange={(e) => setColumns((prev) => ({ ...prev, [key]: e.target.value }))}
                    error={commit.error?.fieldError(`mapping.${key}`)}
                  />
                ))}
                <label className={styles.radioRow}>
                  <input
                    type="checkbox"
                    checked={hasHeader ?? preview.mapping.hasHeader}
                    onChange={(e) => setHasHeader(e.target.checked)}
                  />
                  <span>The first line is a header row, not a transaction</span>
                </label>
              </fieldset>
            )}

            <div className={styles.tableWrap}>
              <table className={styles.table} data-testid="import-rows">
                <caption className={styles.caption}>
                  The first rows of the file, as Trove read them
                </caption>
                <thead>
                  <tr>
                    <th scope="col">Row</th>
                    <th scope="col">Date</th>
                    <th scope="col">Description</th>
                    <th scope="col" className={styles.right}>
                      Amount
                    </th>
                    <th scope="col">What happens</th>
                  </tr>
                </thead>
                <tbody>
                  {preview.rows.map((row) => (
                    <tr key={row.ordinal}>
                      <td>{row.ordinal}</td>
                      <td>{row.date ? format.formatDate(row.date) : '—'}</td>
                      <td>{row.merchantName ?? row.description ?? '—'}</td>
                      <td className={styles.right}>
                        {row.amount === null ? (
                          '—'
                        ) : (
                          /* The file's own currency, stated on every row by the
                             server so a client never has to know the account's. */
                          <MoneyDisplay
                            amount={row.amount}
                            currency={row.currency}
                            colored={false}
                          />
                        )}
                      </td>
                      <td>
                        <QlBadge variant={VERDICT_TONE[row.verdict]}>
                          {VERDICT_LABEL[row.verdict]}
                        </QlBadge>
                        {row.reason && <span className={styles.reason}> {row.reason}</span>}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>

            {commit.isError && (
              <p className={styles.error} role="alert">
                {commitErrorCopy(commit.error)}
              </p>
            )}

            <div className={styles.dialogActions}>
              <QlButton variant="ghost" onClick={onClose}>
                Cancel
              </QlButton>
              <QlButton
                variant="primary"
                loading={commit.isPending}
                disabled={mustChooseFormat}
                onClick={doCommit}
              >
                Import {preview.newCount} transaction{preview.newCount === 1 ? '' : 's'}
              </QlButton>
            </div>
            {mustChooseFormat && (
              <p className={styles.hint} role="status">
                Choose a date format above to continue.
              </p>
            )}
          </div>
        )}

        {/* ------------------------------------------------------- step 3 */}
        {step === 3 && committed && (
          <div className={styles.stack} data-testid="import-done">
            <p className={styles.success} role="status">
              {committed.transactionsNew} transaction
              {committed.transactionsNew === 1 ? '' : 's'} added to {account.displayName}.{' '}
              {committed.transactionsSkipped} were already there.
              {committed.transactionsFailed > 0
                ? ` ${committed.transactionsFailed} could not be read and were left out.`
                : ''}
            </p>
            {committed.errorMessage && (
              <p className={styles.hint} role="status">
                {committed.errorMessage}
              </p>
            )}
            <p className={styles.hint}>
              You can undo this import from the account below for as long as the file is kept.
            </p>
            <div className={styles.dialogActions}>
              <QlButton variant="primary" onClick={onClose}>
                Done
              </QlButton>
            </div>
          </div>
        )}
      </div>
    </QlModal>
  )
}
