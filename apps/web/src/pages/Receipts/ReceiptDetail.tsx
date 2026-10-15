/**
 * One receipt in full: the image, the extracted fields, the tax split, the line
 * items, and the two writes the contract keeps apart.
 *
 * **`PATCH /v1/receipts/{id}` and `PATCH /v1/receipts/{id}/review` are different
 * actions and are different controls here.** The first writes fields; the second
 * records a verdict on what the server extracted. `accepted: false` marks the
 * receipt `FAILED` — the member saying the extraction is not worth correcting —
 * and the image stays stored and downloadable either way. Building the review as
 * "save with a flag" would lose that distinction, which is the one the
 * REVIEW_REQUIRED queue is built on.
 *
 * The patch body carries ONLY what the member changed. There is no way to clear
 * a field back to null through that operation, so sending an untouched field
 * back would be a write, not a no-op.
 */
import { useState } from 'react'
import { QlButton } from '@components/ui/QlButton/QlButton'
import { QlBadge } from '@components/ui/QlBadge/QlBadge'
import { QlInput } from '@components/ui/QlInput/QlInput'
import { QlModal } from '@components/ui/QlModal/QlModal'
import { QlSkeleton } from '@components/ui/QlSkeleton/QlSkeleton'
import { MoneyDisplay } from '@components/MoneyDisplay/MoneyDisplay'
import { useDialogFocus } from '@hooks/useDialogFocus'
import { useUserFormat } from '@hooks/useUserFormat'
import {
  useDeleteReceiptMutation,
  usePatchReceiptMutation,
  useReceipt,
  useReviewReceiptMutation,
} from '@hooks/useReceipts'
import { downloadReceipt, thumbnailPathFor } from '@api/receipts'
import { saveAuthenticatedFile } from '@api/binary'
import { toApiError, type ApiError } from '@api/client'
import { receiptErrorCopy, receiptStatusLabel } from './errorCopy'
import { ReceiptThumbnail } from './ReceiptThumbnail'
import type { ReceiptPatchRequest, ReceiptResponse } from '@api/types'
import styles from './Receipts.module.css'

function ErrorLine({ error, testId }: { error: ApiError; testId: string }) {
  return (
    <p className={styles.error} role="alert" data-testid={testId}>
      {receiptErrorCopy(error)}
    </p>
  )
}

/** The Canadian tax split, shown only for the rows the server actually filled in. */
function TaxSplit({ receipt }: { receipt: ReceiptResponse }) {
  const currency = receipt.extractedCurrency
  const rows: { label: string; amount: string }[] = []
  if (receipt.gstAmount !== null) rows.push({ label: 'GST', amount: receipt.gstAmount })
  if (receipt.pstAmount !== null) rows.push({ label: 'PST', amount: receipt.pstAmount })
  if (receipt.hstAmount !== null) rows.push({ label: 'HST', amount: receipt.hstAmount })
  if (rows.length === 0 || currency === null) return null

  return (
    <div className={styles.stack} data-testid="receipt-tax-split">
      <h3 className={styles.sectionTitle}>Tax on this receipt</h3>
      {rows.map((row) => (
        <div key={row.label} className={styles.row}>
          <span className={styles.label}>{row.label}</span>
          <MoneyDisplay amount={row.amount} currency={currency} colored={false} />
        </div>
      ))}
      <p className={styles.hint}>
        Reverse-calculated from the total for your own province, as a convenience for reading this
        screen. No export carries these figures: the tax-time export is your own amounts and
        labels, with no computed number of any kind.
      </p>
    </div>
  )
}

/** The review verdict. A DECISION, deliberately not a field edit. */
function ReviewPanel({ receipt }: { receipt: ReceiptResponse }) {
  const review = useReviewReceiptMutation()

  return (
    <div className={styles.reviewPanel} data-testid="receipt-review-panel">
      <h3 className={styles.sectionTitle}>This receipt needs your review</h3>
      <p className={styles.note}>
        Nothing went wrong. On an install with no vision model this is the ordinary outcome: the
        image is stored and the fields are yours to fill in. Accepting clears the flag; rejecting
        marks the receipt unusable and keeps the image, which is what to do when the extraction is
        not worth correcting.
      </p>
      {review.isError && <ErrorLine error={review.error} testId="receipt-review-error" />}
      <div className={styles.actions}>
        <QlButton
          variant="primary"
          loading={review.isPending}
          onClick={() => review.mutate({ id: receipt.id, body: { accepted: true } })}
        >
          Accept these fields
        </QlButton>
        <QlButton
          variant="ghost"
          loading={review.isPending}
          onClick={() => review.mutate({ id: receipt.id, body: { accepted: false } })}
        >
          Reject the extraction
        </QlButton>
      </div>
    </div>
  )
}

/** The manual field editor. Sends only what changed. */
function FieldEditor({ receipt }: { receipt: ReceiptResponse }) {
  const patch = usePatchReceiptMutation()
  const [merchant, setMerchant] = useState(receipt.extractedMerchant ?? '')
  const [amount, setAmount] = useState(receipt.extractedAmount ?? '')
  const [date, setDate] = useState(receipt.extractedDate ?? '')
  const [saved, setSaved] = useState(false)

  // Only what the member actually changed. An absent key is left alone; there is
  // no way to clear one back to null here, so resending an untouched field would
  // be a write rather than a no-op.
  const body: ReceiptPatchRequest = {}
  if (merchant !== (receipt.extractedMerchant ?? '') && merchant.trim() !== '') {
    body.extractedMerchant = merchant
  }
  if (amount !== (receipt.extractedAmount ?? '') && amount.trim() !== '') {
    body.extractedAmount = amount
  }
  if (date !== (receipt.extractedDate ?? '') && date.trim() !== '') {
    body.extractedDate = date
  }
  /**
   * Nothing changed means there is nothing to send, and the operation refuses an
   * empty body with a 400 ("change at least one field"). Without this the
   * harmless "let me just save that" click on an untouched form ALWAYS produces
   * an error banner, which teaches the member to distrust the banner.
   */
  const dirty = Object.keys(body).length > 0

  function save() {
    if (!dirty) return
    setSaved(false)
    patch.mutate({ id: receipt.id, patch: body }, { onSuccess: () => setSaved(true) })
  }

  return (
    <div className={styles.stack}>
      <h3 className={styles.sectionTitle}>Fields</h3>
      <QlInput
        label="Merchant"
        value={merchant}
        onChange={(e) => setMerchant(e.target.value)}
        error={patch.error?.fieldError('extractedMerchant')}
      />
      <QlInput
        label="Total"
        inputMode="decimal"
        value={amount}
        onChange={(e) => setAmount(e.target.value)}
        hint="The amount as printed, in the currency on the receipt."
        error={patch.error?.fieldError('extractedAmount')}
      />
      <QlInput
        label="Date on the receipt"
        type="date"
        value={date}
        onChange={(e) => setDate(e.target.value)}
        error={patch.error?.fieldError('extractedDate')}
      />
      {patch.isError && <ErrorLine error={patch.error} testId="receipt-patch-error" />}
      {saved && (
        <p className={styles.status} role="status">
          Saved.
        </p>
      )}
      <div className={styles.actions}>
        <QlButton variant="primary" loading={patch.isPending} disabled={!dirty} onClick={save}>
          Save fields
        </QlButton>
        {!dirty && (
          <span className={styles.hint} data-testid="receipt-fields-clean">
            Change a field to save it.
          </span>
        )}
      </div>
    </div>
  )
}

export interface ReceiptDetailProps {
  id: string
  onClose: () => void
  onDeleted: () => void
}

export function ReceiptDetail({ id, onClose, onDeleted }: ReceiptDetailProps) {
  const receipt = useReceipt(id)
  const remove = useDeleteReceiptMutation()
  const format = useUserFormat()
  const ref = useDialogFocus<HTMLDivElement>(true)
  const [confirmDelete, setConfirmDelete] = useState(false)
  const [downloadError, setDownloadError] = useState<ApiError | null>(null)
  const [downloading, setDownloading] = useState(false)

  async function download() {
    setDownloadError(null)
    setDownloading(true)
    try {
      // Authenticated bytes, then handed over as a save. Never a plain link:
      // there is no presigned path and no token in a query string.
      const file = await downloadReceipt(id)
      saveAuthenticatedFile(file, `receipt-${id}.jpg`)
    } catch (err) {
      setDownloadError(toApiError(err))
    } finally {
      setDownloading(false)
    }
  }

  return (
    <QlModal isOpen onClose={onClose} title="Receipt" size="lg">
      <div ref={ref} tabIndex={-1} className={styles.detailGrid} data-testid="receipt-detail">
        {receipt.isPending && <QlSkeleton rows={3} data-testid="receipt-detail-loading" />}
        {receipt.isError && <ErrorLine error={receipt.error} testId="receipt-detail-error" />}

        {receipt.data && (
          <>
            <div className={styles.row}>
              <span className={styles.label}>Status</span>
              <span className={styles.value} data-testid="receipt-detail-status">
                <QlBadge variant={receipt.data.reviewRequired ? 'amber' : 'neutral'}>
                  {receiptStatusLabel(receipt.data.status, receipt.data.reviewRequired)}
                </QlBadge>
              </span>
            </div>

            <ReceiptThumbnail
              path={thumbnailPathFor(receipt.data)}
              merchant={receipt.data.extractedMerchant}
              date={receipt.data.extractedDate}
              className={styles.detailImage}
              data-testid="receipt-detail-image"
            />

            {receipt.data.errorMessage !== null && (
              <p className={styles.error} role="status" data-testid="receipt-detail-message">
                {receipt.data.errorMessage}
              </p>
            )}

            {receipt.data.reviewRequired && <ReviewPanel receipt={receipt.data} />}

            <div className={styles.row}>
              <span className={styles.label}>Total</span>
              <span className={styles.value}>
                {receipt.data.extractedAmount !== null &&
                receipt.data.extractedCurrency !== null ? (
                  <MoneyDisplay
                    amount={receipt.data.extractedAmount}
                    currency={receipt.data.extractedCurrency}
                    colored={false}
                  />
                ) : (
                  <span className={styles.hint}>Not read yet</span>
                )}
              </span>
            </div>
            <div className={styles.row}>
              <span className={styles.label}>Uploaded</span>
              <span className={styles.value}>{format.formatDateTime(receipt.data.createdAt)}</span>
            </div>
            {receipt.data.matchedTransactionId !== null && (
              <div className={styles.row}>
                <span className={styles.label}>Attached to a transaction</span>
                <span className={styles.value} data-testid="receipt-matched">
                  Yes
                </span>
              </div>
            )}

            <TaxSplit receipt={receipt.data} />

            {receipt.data.lineItems.length > 0 && (
              <div className={styles.stack}>
                <h3 className={styles.sectionTitle}>Line items</h3>
                <table className={styles.lineItems} data-testid="receipt-line-items">
                  <thead>
                    <tr>
                      <th scope="col">Item</th>
                      <th scope="col">Quantity</th>
                      <th scope="col">Unit price</th>
                      <th scope="col">Total</th>
                    </tr>
                  </thead>
                  <tbody>
                    {receipt.data.lineItems.map((item) => (
                      <tr key={item.id}>
                        <td>{item.description}</td>
                        <td>{item.quantity}</td>
                        <td>
                          {receipt.data.extractedCurrency !== null && (
                            <MoneyDisplay
                              amount={item.unitPrice}
                              currency={receipt.data.extractedCurrency}
                              colored={false}
                            />
                          )}
                        </td>
                        <td>
                          {receipt.data.extractedCurrency !== null && (
                            <MoneyDisplay
                              amount={item.totalPrice}
                              currency={receipt.data.extractedCurrency}
                              colored={false}
                            />
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}

            <FieldEditor receipt={receipt.data} />

            {downloadError && <ErrorLine error={downloadError} testId="receipt-download-error" />}

            <div className={styles.actions}>
              <QlButton variant="ghost" loading={downloading} onClick={() => void download()}>
                Download the image
              </QlButton>
              {confirmDelete ? (
                <>
                  <span className={styles.note}>
                    Delete this receipt, its line items and the stored image?
                  </span>
                  <QlButton
                    variant="primary"
                    loading={remove.isPending}
                    onClick={() =>
                      remove.mutate(id, {
                        onSuccess: () => {
                          onDeleted()
                          onClose()
                        },
                      })
                    }
                  >
                    Yes, delete it
                  </QlButton>
                  <QlButton variant="ghost" onClick={() => setConfirmDelete(false)}>
                    Keep it
                  </QlButton>
                </>
              ) : (
                <QlButton variant="ghost" onClick={() => setConfirmDelete(true)}>
                  Delete this receipt
                </QlButton>
              )}
            </div>
            {remove.isError && <ErrorLine error={remove.error} testId="receipt-delete-error" />}
          </>
        )}
      </div>
    </QlModal>
  )
}
