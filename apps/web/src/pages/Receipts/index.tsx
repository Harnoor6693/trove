/**
 * Receipts — the household's stored receipt images, paged, with the upload that
 * starts a background pipeline.
 *
 * Three things on this page are contract, not taste:
 *
 *  - **`GET /v1/receipts` is paged and its 200 requires both `data` and `meta`.**
 *    The pager below is driven entirely by the server's `meta`; no page count is
 *    invented from `rows.length`, which is the silent truncation of the
 *    household's own records that step 201 removed from transactions and that
 *    the mobile client still has.
 *  - **Upload answers 202 and the row's fields do not exist yet.** So the screen
 *    does not render the new receipt as though it were complete: it polls
 *    `GET /v1/receipts/{id}/status` and shows the real status until `COMPLETE`
 *    or `FAILED`, and stops polling there.
 *  - **The images are authenticated and are not `<img src>`.** See
 *    `ReceiptThumbnail`.
 *
 * `COMPLETE` with `reviewRequired: true` is the ordinary outcome on an install
 * with no vision model, not a failure, and the copy says so.
 */
import { useState } from 'react'
import { QlButton } from '@components/ui/QlButton/QlButton'
import { QlBadge } from '@components/ui/QlBadge/QlBadge'
import { QlCard } from '@components/ui/QlCard/QlCard'
import { QlSkeleton } from '@components/ui/QlSkeleton/QlSkeleton'
import { EmptyState } from '@components/EmptyState/EmptyState'
import { MoneyDisplay } from '@components/MoneyDisplay/MoneyDisplay'
import { useUserFormat } from '@hooks/useUserFormat'
import {
  useReceipts,
  useReceiptStatus,
  useUploadReceiptMutation,
} from '@hooks/useReceipts'
import { ACCEPTED_RECEIPT_TYPES } from '@api/receipts'
import { ReceiptDetail } from './ReceiptDetail'
import { ReceiptThumbnail } from './ReceiptThumbnail'
import { receiptErrorCopy, receiptStatusLabel } from './errorCopy'
import type { ReceiptsQuery } from '@api/types'
import styles from './Receipts.module.css'

/** Rows per page. Well inside the operation's maximum of 200. */
const PAGE_SIZE = 20

/* -------------------------------------------------------------- upload box */

/**
 * The upload, and the poll that follows it.
 *
 * The accepted formats are stated BEFORE the member picks a file, and so is the
 * fact that there is a size ceiling. **The ceiling's number is not stated**: the
 * contract does not carry one — `UploadTooLarge` names three ceilings and gives
 * a figure for none of them — so inventing "10 MB" here would be a number on a
 * screen that no operation supports. When the server refuses, its own 413
 * message (which names bytes) is what is shown.
 */
function UploadBox() {
  const upload = useUploadReceiptMutation()
  const [uploadedId, setUploadedId] = useState<string | null>(null)
  const status = useReceiptStatus(uploadedId)

  function pick(file: File | undefined) {
    if (!file) return
    setUploadedId(null)
    upload.mutate({ file }, { onSuccess: (created) => setUploadedId(created.id) })
  }

  return (
    <QlCard>
      <div className={styles.stack}>
        <h2 className={styles.sectionTitle}>Add a receipt</h2>
        <div className={styles.uploadBox}>
          <label className={styles.selectLabel} htmlFor="receipt-file">
            Choose a receipt image
          </label>
          <input
            id="receipt-file"
            className={styles.fileInput}
            type="file"
            accept={ACCEPTED_RECEIPT_TYPES.join(',')}
            aria-describedby="receipt-file-hint"
            disabled={upload.isPending}
            onChange={(e) => pick(e.target.files?.[0])}
          />
          <p className={styles.hint} id="receipt-file-hint" data-testid="receipt-upload-hint">
            JPEG or PNG only. HEIC and PDF are not accepted. There is a size limit and a total
            storage allowance for your household; the server states the figure if a file is over
            one. The image is re-encoded before it is stored, which removes the location your
            camera wrote into it.
          </p>
        </div>

        {upload.isPending && (
          <p className={styles.status} role="status">
            Uploading…
          </p>
        )}
        {upload.isError && (
          <p className={styles.error} role="alert" data-testid="receipt-upload-error">
            {receiptErrorCopy(upload.error)}
          </p>
        )}
        {uploadedId !== null && status.data && (
          <p className={styles.status} role="status" data-testid="receipt-upload-status">
            {receiptStatusLabel(status.data.status, status.data.reviewRequired)}
          </p>
        )}
      </div>
    </QlCard>
  )
}

/* -------------------------------------------------------------------- page */

export default function Receipts() {
  const [query, setQuery] = useState<ReceiptsQuery>({ page: 0, limit: PAGE_SIZE })
  const [openId, setOpenId] = useState<string | null>(null)
  const page = useReceipts(query)
  const format = useUserFormat()

  const rows = page.data?.rows ?? []
  const meta = page.data?.meta
  const currentPage = meta?.page ?? 0
  const limit = meta?.limit ?? PAGE_SIZE
  const total = meta?.total ?? 0
  const firstShown = total === 0 ? 0 : currentPage * limit + 1
  const lastShown = Math.min(total, currentPage * limit + rows.length)
  const hasNext = (currentPage + 1) * limit < total
  const hasPrevious = currentPage > 0

  return (
    <div className={styles.page}>
      <header className={styles.header}>
        <div>
          <h1 className={styles.heading}>Receipts</h1>
          <p className={styles.lede}>
            Photographs of your household&rsquo;s spending, stored on your own server. Every member
            of the household sees every receipt. The images are behind your session &mdash; there
            is no public link to one and nothing here is shared with anybody.
          </p>
        </div>
      </header>

      <UploadBox />

      {page.isPending && <QlSkeleton rows={3} data-testid="receipts-loading" />}

      {page.isError && (
        <QlCard>
          <div className={styles.stack} data-testid="receipts-error">
            <p className={styles.error} role="alert">
              {receiptErrorCopy(page.error)}
            </p>
            <div>
              <QlButton variant="primary" onClick={() => void page.refetch()}>
                Try again
              </QlButton>
            </div>
          </div>
        </QlCard>
      )}

      {page.data && rows.length === 0 && (
        <QlCard>
          <EmptyState title="No receipts yet" data-testid="receipts-empty">
            <p>
              Add one above. Without a vision model configured, Trove stores the image and leaves
              the fields for you to fill in &mdash; which is the ordinary way this works, not a
              failure.
            </p>
          </EmptyState>
        </QlCard>
      )}

      {page.data && rows.length > 0 && (
        <>
          <QlCard padding="none">
            <div className={styles.tableWrap}>
              <table className={styles.table} data-testid="receipts-table">
                <caption>
                  Showing {firstShown}&ndash;{lastShown} of {total} receipts
                </caption>
                <thead>
                  <tr>
                    <th scope="col">
                      <span className={styles.srOnly}>Image</span>
                    </th>
                    <th scope="col">Merchant</th>
                    <th scope="col">Date</th>
                    <th scope="col">Status</th>
                    <th scope="col" className={styles.right}>
                      Total
                    </th>
                    <th scope="col">
                      <span className={styles.srOnly}>Actions</span>
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map((r) => (
                    <tr key={r.id} data-testid={`receipt-row-${r.id}`}>
                      <td>
                        {/* The list's thumbnail path is derived from the id: the
                            summary schema does not carry `thumbnailUrl`, only the
                            full row does. */}
                        <ReceiptThumbnail
                          path={`/v1/receipts/${encodeURIComponent(r.id)}/thumbnail`}
                          merchant={r.extractedMerchant}
                          date={r.extractedDate}
                        />
                      </td>
                      <td>{r.extractedMerchant ?? <span className={styles.hint}>Not read yet</span>}</td>
                      <td>
                        {r.extractedDate ? (
                          format.formatDate(r.extractedDate)
                        ) : (
                          <span className={styles.hint}>&mdash;</span>
                        )}
                      </td>
                      <td>
                        <QlBadge variant={r.reviewRequired ? 'amber' : 'neutral'}>
                          {receiptStatusLabel(r.status, r.reviewRequired)}
                        </QlBadge>
                      </td>
                      <td className={styles.right}>
                        {r.extractedAmount !== null && r.extractedCurrency !== null ? (
                          <MoneyDisplay
                            amount={r.extractedAmount}
                            currency={r.extractedCurrency}
                            colored={false}
                          />
                        ) : (
                          <span className={styles.hint}>&mdash;</span>
                        )}
                      </td>
                      <td>
                        <QlButton variant="ghost" onClick={() => setOpenId(r.id)}>
                          Open receipt from {r.extractedMerchant ?? 'an unnamed merchant'}
                        </QlButton>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </QlCard>

          {/* Driven entirely by the server's `meta`. */}
          <nav className={styles.pager} aria-label="Receipt pages" data-testid="receipts-pager">
            <QlButton
              variant="ghost"
              disabled={!hasPrevious}
              onClick={() => setQuery((prev) => ({ ...prev, page: (prev.page ?? 0) - 1 }))}
            >
              Previous page
            </QlButton>
            <span className={styles.pagerStatus} role="status">
              Page {currentPage + 1} of {Math.max(1, Math.ceil(total / limit))}
            </span>
            <QlButton
              variant="ghost"
              disabled={!hasNext}
              onClick={() => setQuery((prev) => ({ ...prev, page: (prev.page ?? 0) + 1 }))}
            >
              Next page
            </QlButton>
          </nav>
        </>
      )}

      {openId !== null && (
        <ReceiptDetail
          id={openId}
          onClose={() => setOpenId(null)}
          onDeleted={() => setOpenId(null)}
        />
      )}
    </div>
  )
}
