/**
 * Tax export runs — start one, poll it to completion, download it, delete it
 * (§4.12, step 203).
 *
 * **The download is authenticated and is not a link.** `downloadTaxExportRun`
 * fetches the bytes through the shared, session-carrying client (`@api/binary`,
 * landed by step 204) and hands them to the member as a save; the object URL
 * is revoked immediately after. A run that is not yet `COMPLETE` answers 409
 * `ExportNotReady`, read here as "still generating" — never as a failure.
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
  useCreateTaxExportRunMutation,
  useDeleteTaxExportRunMutation,
  useTaxExportRuns,
} from '@hooks/useTaxExport'
import { downloadTaxExportRun } from '@api/taxexport'
import { saveAuthenticatedFile } from '@api/binary'
import { toApiError, type ApiError } from '@api/client'
import { taxExportErrorCopy } from './errorCopy'
import type { CreateTaxExportRunRequest, TaxExportFormat, TaxExportRunResponse } from '@api/types'
import styles from './TaxExport.module.css'

const STATUS_BADGE: Record<
  TaxExportRunResponse['status'],
  { variant: 'sage' | 'amber' | 'red' | 'neutral'; label: string }
> = {
  PENDING: { variant: 'neutral', label: 'Waiting to start' },
  GENERATING: { variant: 'amber', label: 'Generating' },
  COMPLETE: { variant: 'sage', label: 'Ready' },
  FAILED: { variant: 'red', label: 'Failed' },
}

const CURRENT_YEAR = new Date().getFullYear()

function CreateRunDialog({ onClose }: { onClose: () => void }) {
  const create = useCreateTaxExportRunMutation()
  const ref = useDialogFocus<HTMLDivElement>(true)
  const [taxYear, setTaxYear] = useState(String(CURRENT_YEAR))
  const [format, setFormat] = useState<TaxExportFormat>('CSV')

  function submit(e: React.FormEvent) {
    e.preventDefault()
    const body: CreateTaxExportRunRequest = { taxYear: Number(taxYear), format }
    create.mutate(body, { onSuccess: onClose })
  }

  return (
    <QlModal isOpen onClose={onClose} title="Start a tax-year export">
      <div ref={ref} tabIndex={-1} className={styles.stack} data-testid="create-run-dialog">
        <p className={styles.hint}>
          This exports your household's categorised transactions for the whole calendar year,
          under your own tax-line labels. It computes nothing — no rate, no bracket, no total.
          Your household mates' spending is included; only you can see or download this run.
        </p>
        <form className={styles.stack} onSubmit={submit}>
          <QlInput
            label="Tax year"
            inputMode="numeric"
            value={taxYear}
            onChange={(e) => setTaxYear(e.target.value)}
            error={create.error?.fieldError('taxYear')}
            required
          />
          <label className={styles.selectLabel} htmlFor="run-format">
            Format
          </label>
          <select
            id="run-format"
            className={styles.select}
            value={format}
            onChange={(e) => setFormat(e.target.value as TaxExportFormat)}
          >
            <option value="CSV">CSV (for your accountant's software)</option>
            <option value="PDF">PDF (for printing)</option>
          </select>
          {create.isError && !create.error.fieldError('taxYear') && (
            <p className={styles.error} role="alert">
              {taxExportErrorCopy(create.error)}
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

function DeleteRunDialog({
  run,
  onClose,
}: {
  run: TaxExportRunResponse
  onClose: () => void
}) {
  const remove = useDeleteTaxExportRunMutation()
  const ref = useDialogFocus<HTMLDivElement>(true)
  return (
    <QlModal isOpen onClose={onClose} title={`Delete the ${run.taxYear} export?`}>
      <div ref={ref} tabIndex={-1} className={styles.stack} data-testid="delete-run-dialog">
        <p className={styles.hint}>This removes the run and its generated file together.</p>
        {remove.isError && (
          <p className={styles.error} role="alert">
            {taxExportErrorCopy(remove.error)}
          </p>
        )}
        <div className={styles.dialogActions}>
          <QlButton variant="ghost" onClick={onClose}>
            Keep it
          </QlButton>
          <QlButton
            variant="danger"
            loading={remove.isPending}
            onClick={() => remove.mutate(run.id, { onSuccess: onClose })}
          >
            Delete
          </QlButton>
        </div>
      </div>
    </QlModal>
  )
}

interface DownloadState {
  runId: string | null
  stillGenerating: boolean
  error: ApiError | null
}

export function RunsPanel() {
  const [page, setPage] = useState(0)
  const runs = useTaxExportRuns({ page, limit: 20 })
  const format = useUserFormat()
  const [showCreate, setShowCreate] = useState(false)
  const [toDelete, setToDelete] = useState<TaxExportRunResponse | null>(null)
  const [download, setDownload] = useState<DownloadState>({
    runId: null,
    stillGenerating: false,
    error: null,
  })

  async function handleDownload(run: TaxExportRunResponse) {
    setDownload({ runId: run.id, stillGenerating: false, error: null })
    try {
      const file = await downloadTaxExportRun(run.id)
      saveAuthenticatedFile(file, `tax-export-${run.taxYear}.${run.format.toLowerCase()}`)
      setDownload({ runId: null, stillGenerating: false, error: null })
    } catch (err) {
      const apiErr = toApiError(err)
      // PENDING, GENERATING *and* FAILED all answer 409 on download. A FAILED run
      // will never become COMPLETE, so "still generating" would be permanently
      // misleading for it — the Download button is not even offered for a FAILED
      // row (see the `status === 'COMPLETE'` guard below), but keep this branch
      // honest for the PENDING/GENERATING race this button IS offered for.
      if (apiErr.code === 'conflict' && run.status !== 'FAILED') {
        setDownload({ runId: run.id, stillGenerating: true, error: null })
      } else {
        setDownload({ runId: run.id, stillGenerating: false, error: apiErr })
      }
    }
  }

  const rows = runs.data?.rows ?? []
  const meta = runs.data?.meta

  const columns: QlTableColumn<TaxExportRunResponse & Record<string, unknown>>[] = [
    { key: 'taxYear', header: 'Year' },
    { key: 'format', header: 'Format' },
    {
      key: 'status',
      header: 'Status',
      render: (row) => (
        <QlBadge variant={STATUS_BADGE[row.status].variant}>{STATUS_BADGE[row.status].label}</QlBadge>
      ),
    },
    {
      key: 'createdAt',
      header: 'Started',
      render: (row) => format.formatDateTime(row.createdAt),
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
      render: (row) => (
        <div className={styles.rowActions}>
          {row.status !== 'FAILED' && (
            <QlButton
              size="sm"
              variant="ghost"
              loading={download.runId === row.id && !download.stillGenerating && !download.error}
              onClick={() => void handleDownload(row)}
            >
              Download
            </QlButton>
          )}
          <QlButton size="sm" variant="ghost" onClick={() => setToDelete(row)}>
            Delete
          </QlButton>
        </div>
      ),
    },
  ]

  return (
    <QlCard>
      <div className={styles.header}>
        <h2 className={styles.sectionTitle}>Export runs</h2>
        <QlButton size="sm" variant="primary" onClick={() => setShowCreate(true)}>
          Start export
        </QlButton>
      </div>

      {runs.isPending && <QlSkeleton data-testid="runs-skeleton" />}
      {runs.isError && (
        <p className={styles.error} role="alert">
          {taxExportErrorCopy(runs.error)}
        </p>
      )}
      {runs.data && rows.length === 0 && (
        <EmptyState title="No exports yet">
          <p>Start one for a tax year to generate a file for your accountant.</p>
        </EmptyState>
      )}
      {runs.data && rows.length > 0 && (
        <div className={styles.tableWrap} data-testid="runs-table">
          <QlTable
            columns={columns}
            rows={rows as (TaxExportRunResponse & Record<string, unknown>)[]}
          />
        </div>
      )}
      {download.stillGenerating && (
        <p className={styles.hint} role="status" data-testid="download-still-generating">
          That export is still generating. Try the download again once it shows "Ready".
        </p>
      )}
      {download.error && (
        <p className={styles.error} role="alert" data-testid="download-error">
          {taxExportErrorCopy(download.error)}
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

      {showCreate && <CreateRunDialog onClose={() => setShowCreate(false)} />}
      {toDelete && <DeleteRunDialog run={toDelete} onClose={() => setToDelete(null)} />}
    </QlCard>
  )
}
