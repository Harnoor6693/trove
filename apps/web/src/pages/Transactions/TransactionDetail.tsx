/**
 * One transaction, and the four things a member may change about it.
 *
 * **The presence-meaningful trap, and how this file avoids it.**
 * `TransactionPatchRequest` is a true partial update: a key the body omits is
 * left alone, and a key present with `null` is CLEARED. A form that spreads its
 * whole draft state into the body would therefore rewrite every field the member
 * never touched — and, worse, would make "unlink this transfer" indistinguishable
 * from "change the notes", because both would carry `transferGroupId`.
 *
 * So this component never sends its state. It sends a DIFF: `buildPatch` compares
 * the draft against the row that came back from the server and emits only the
 * keys that actually moved, mapping an emptied text field to an explicit `null`
 * (which is the member saying "clear this") and leaving an untouched one out
 * entirely.
 *
 * **Transfers are not a route.** The contract has no transfer endpoint; a leg is
 * marked by patching `transferGroupId` onto the row and unlinked with `null`.
 * Linking two legs is therefore two patches carrying the same id: the other
 * leg's group when it already has one (joining an existing pair), and a freshly
 * minted uuid when it does not. Those two writes are AWAITED IN SEQUENCE through
 * `mutateAsync`: fired with `mutate`, the second detaches React Query's observer
 * from the first, so a first leg that answered 404, 409, 429 or a CSRF 403 failed
 * in total silence while the second leg was written anyway — one row marked as a
 * transfer and its partner not. Awaiting shows a failed first leg and never
 * attempts the second.
 *
 * **The other leg is looked up, not picked off the page.** The candidates are
 * fetched here from `listTransactions` over a date window around this row, across
 * every account, because a transfer's other leg is on a DIFFERENT account by
 * definition: a picker filled from the list page's rows could not offer it at all
 * while the list was filtered by account, and even unfiltered it required both
 * legs to land on the same twenty-five-row page.
 */
import { useState } from 'react'
import { QlButton } from '@components/ui/QlButton/QlButton'
import { QlInput } from '@components/ui/QlInput/QlInput'
import { QlModal } from '@components/ui/QlModal/QlModal'
import { QlBadge } from '@components/ui/QlBadge/QlBadge'
import { QlSkeleton } from '@components/ui/QlSkeleton/QlSkeleton'
import { EmptyState } from '@components/EmptyState/EmptyState'
import { MoneyDisplay } from '@components/MoneyDisplay/MoneyDisplay'
import { useUserFormat } from '@hooks/useUserFormat'
import { useDialogFocus } from '@hooks/useDialogFocus'
import { useCategories } from '@hooks/useCategories'
import {
  useDeleteTransactionMutation,
  usePatchTransactionMutation,
  useTransaction,
  useTransactions,
} from '@hooks/useTransactions'
import { isDirectional, signedAmount } from '@api/transactionDirection'
import { detailErrorCopy } from './errorCopy'
import type { TransactionPatchRequest, TransactionResponse } from '@api/types'
import styles from './Transactions.module.css'

/** The editable text fields, as the form holds them. */
interface Draft {
  merchantName: string
  notes: string
  tags: string
}

function draftOf(t: TransactionResponse): Draft {
  return {
    merchantName: t.merchantName ?? '',
    notes: t.notes ?? '',
    tags: (t.tags ?? []).join(', '),
  }
}

function tagList(value: string): string[] {
  return value
    .split(',')
    .map((s) => s.trim())
    .filter((s) => s !== '')
}

/**
 * Only what changed. An emptied field becomes an explicit `null` — the member
 * clearing it — and an untouched field is absent, which the server reads as
 * "leave it alone".
 */
function buildPatch(original: TransactionResponse, draft: Draft): TransactionPatchRequest {
  const patch: TransactionPatchRequest = {}
  const base = draftOf(original)
  if (draft.merchantName !== base.merchantName) {
    patch.merchantName = draft.merchantName === '' ? null : draft.merchantName
  }
  if (draft.notes !== base.notes) {
    patch.notes = draft.notes === '' ? null : draft.notes
  }
  if (draft.tags !== base.tags) {
    const list = tagList(draft.tags)
    patch.tags = list.length === 0 ? null : list
  }
  return patch
}

/** A group id for a brand-new pair. `randomUUID` is present in every target browser. */
function newTransferGroupId(): string {
  return crypto.randomUUID()
}

export interface TransactionDetailProps {
  id: string
  onClose: () => void
}

/** How far either side of this row the picker looks for the other leg. */
const WINDOWS = [
  { days: 7, label: 'within a week' },
  { days: 30, label: 'within a month' },
  { days: 90, label: 'within three months' },
] as const

/**
 * A date `days` either side of `iso`. This is a SEARCH window, not a money
 * boundary: no figure on this screen is computed from it, and every amount and
 * every total still comes from the server. (A day boundary that decides a
 * member's spend is the server's alone — see the Today page.)
 */
function shiftDate(iso: string, days: number): string {
  const d = new Date(`${iso}T00:00:00Z`)
  d.setUTCDate(d.getUTCDate() + days)
  return d.toISOString().slice(0, 10)
}

export function TransactionDetail({ id, onClose }: TransactionDetailProps) {
  const query = useTransaction(id)
  const patch = usePatchTransactionMutation()
  const remove = useDeleteTransactionMutation()
  const categories = useCategories()
  const format = useUserFormat()
  const dialogRef = useDialogFocus<HTMLDivElement>(true)

  const [draft, setDraft] = useState<Draft | null>(null)
  const [linkTo, setLinkTo] = useState('')
  const [windowDays, setWindowDays] = useState<number>(WINDOWS[0].days)
  const [confirmingDelete, setConfirmingDelete] = useState(false)

  const row = query.data
  const current = draft ?? (row ? draftOf(row) : null)

  /**
   * The other leg, looked for across EVERY account (no `accountId` filter — the
   * partner is on another account by definition) within a window of this row's
   * date. Disabled until the row itself has arrived, because the window is the
   * row's own date.
   */
  const needsCandidates = row !== undefined && row.transferGroupId === null
  const candidateQuery = useTransactions(
    {
      from: row ? shiftDate(row.date, -windowDays) : undefined,
      to: row ? shiftDate(row.date, windowDays) : undefined,
      limit: 100,
      sort: 'date,desc',
    },
    { enabled: needsCandidates }
  )
  const candidates = (candidateQuery.data?.rows ?? []).filter((c) => c.id !== id)

  function save() {
    if (!row || !current) return
    const body = buildPatch(row, current)
    // Nothing changed: there is no request to make, and an empty PATCH would be
    // a write that says nothing.
    if (Object.keys(body).length === 0) return
    patch.mutate({ id: row.id, patch: body }, { onSuccess: () => setDraft(null) })
  }

  function assignCategory(categoryId: string) {
    if (!row) return
    // One key, because one thing changed. `''` is the member choosing
    // "uncategorised", which is an explicit clear, not an omission.
    patch.mutate({ id: row.id, patch: { categoryId: categoryId === '' ? null : categoryId } })
  }

  async function linkTransfer() {
    if (!row || linkTo === '') return
    const other = candidates.find((c) => c.id === linkTo)
    if (!other) return
    const groupId = other.transferGroupId ?? newTransferGroupId()
    try {
      // Awaited, and in this order: this row first, then the partner. `mutate`
      // twice on one observer loses the first rejection entirely — the second
      // call detaches the observer and `mutate` swallows the promise — so a
      // refused first leg used to leave the partner marked as half a transfer
      // with nothing on screen to say so.
      await patch.mutateAsync({ id: row.id, patch: { transferGroupId: groupId } })
      if (other.transferGroupId === null) {
        await patch.mutateAsync({ id: other.id, patch: { transferGroupId: groupId } })
      }
      setLinkTo('')
    } catch {
      // The mutation's own error state is rendered below; nothing is retried
      // here and the second leg is deliberately not attempted.
    }
  }

  function unlinkTransfer() {
    if (!row) return
    // The explicit null IS the request. Omitting the key would leave the leg linked.
    patch.mutate({ id: row.id, patch: { transferGroupId: null } })
  }

  return (
    <QlModal isOpen onClose={onClose} size="lg" title="Transaction">
      <div ref={dialogRef} tabIndex={-1} className={styles.stack} data-testid="transaction-detail">
        {query.isPending && (
          <div role="status">
            <QlSkeleton variant="row" rows={3} data-testid="detail-skeleton" />
            <span className={styles.srOnly}>Loading transaction</span>
          </div>
        )}

        {query.isError && (
          <div data-testid="detail-error" className={styles.stack}>
            <EmptyState title="We couldn’t find that transaction">
              <p role="alert" className={styles.error}>
                {detailErrorCopy(query.error)}
              </p>
            </EmptyState>
            <div className={styles.dialogActions}>
              <QlButton variant="primary" onClick={onClose}>
                Back to the list
              </QlButton>
            </div>
          </div>
        )}

        {row && current && (
          <>
            <div className={styles.detailHead}>
              {/* Direction from the contract's reading of the type, not from
                  "CREDIT or else negative": a REFUND is money coming back. */}
              <MoneyDisplay
                amount={signedAmount(row)}
                currency={row.currency}
                colored={isDirectional(row.type)}
                data-testid="detail-amount"
              />
              <span className={styles.detailMeta}>
                {format.formatDate(row.date)} · {row.type}
                {row.pending ? ' · Pending' : ''}
                {row.manual ? ' · Entered by hand' : ''}
              </span>
              {row.transferGroupId && (
                <QlBadge variant="blue">Part of a transfer</QlBadge>
              )}
            </div>

            <label className={styles.selectLabel} htmlFor="detail-category">
              Category
            </label>
            <select
              id="detail-category"
              className={styles.select}
              data-testid="detail-category"
              value={row.categoryId ?? ''}
              onChange={(e) => assignCategory(e.target.value)}
            >
              <option value="">Uncategorised</option>
              {(categories.data ?? []).map((c) => (
                <option key={c.id} value={c.id}>
                  {c.name}
                </option>
              ))}
            </select>

            <QlInput
              label="Merchant"
              value={current.merchantName}
              onChange={(e) => setDraft({ ...current, merchantName: e.target.value })}
              error={patch.error?.fieldError('merchantName')}
            />
            <QlInput
              label="Notes"
              value={current.notes}
              onChange={(e) => setDraft({ ...current, notes: e.target.value })}
              error={patch.error?.fieldError('notes')}
            />
            <QlInput
              label="Tags (comma separated)"
              value={current.tags}
              onChange={(e) => setDraft({ ...current, tags: e.target.value })}
              error={patch.error?.fieldError('tags')}
            />

            {/* Amount, date, account and type are not writable: a wrong ingested
                amount is a source problem, and a wrong manual one is a delete
                and a re-entry. They are shown, not offered. */}
            <p className={styles.hint}>
              The amount, date, account and type of a transaction are not editable. A figure that
              came from your bank is corrected at the bank; one you entered by hand is deleted and
              entered again.
            </p>

            <fieldset className={styles.fieldset} data-testid="transfer-controls">
              <legend className={styles.legend}>Transfer between your own accounts</legend>
              {row.transferGroupId ? (
                <>
                  <p className={styles.hint}>
                    This row is one leg of a transfer, so it is left out of every spending and
                    income figure.
                  </p>
                  <QlButton variant="ghost" onClick={unlinkTransfer}>
                    This is not a transfer
                  </QlButton>
                </>
              ) : (
                <>
                  <label className={styles.selectLabel} htmlFor="transfer-window">
                    Look for the other side
                  </label>
                  <select
                    id="transfer-window"
                    className={styles.select}
                    value={windowDays}
                    onChange={(e) => {
                      setWindowDays(Number(e.target.value))
                      setLinkTo('')
                    }}
                  >
                    {WINDOWS.map((w) => (
                      <option key={w.days} value={w.days}>
                        {w.label} of {format.formatDate(row.date)}
                      </option>
                    ))}
                  </select>
                  <label className={styles.selectLabel} htmlFor="transfer-other-leg">
                    Which transaction is the other side of it?
                  </label>
                  <select
                    id="transfer-other-leg"
                    className={styles.select}
                    value={linkTo}
                    disabled={candidates.length === 0}
                    onChange={(e) => setLinkTo(e.target.value)}
                  >
                    <option value="">Choose a transaction</option>
                    {candidates.map((c) => (
                      <option key={c.id} value={c.id}>
                        {format.formatDate(c.date)} · {c.merchantName ?? c.description ?? c.id} ·{' '}
                        {c.amount} {c.currency}
                      </option>
                    ))}
                  </select>
                  {/* The picker searches the ledger itself, across every
                      account, so it says what it searched — and, when that
                      found nothing, offers a wider window instead of a silently
                      short list. */}
                  <p className={styles.hint} data-testid="transfer-window-note" role="status">
                    {candidateQuery.isPending
                      ? 'Looking through your other transactions…'
                      : candidates.length === 0
                        ? 'No other transaction in that window, on any account. Try a wider one.'
                        : `${candidates.length} transaction${candidates.length === 1 ? '' : 's'} on every account in that window.`}
                  </p>
                  {candidateQuery.isError && (
                    <p className={styles.error} role="alert">
                      {detailErrorCopy(candidateQuery.error)}
                    </p>
                  )}
                  <QlButton
                    variant="ghost"
                    disabled={linkTo === ''}
                    loading={patch.isPending}
                    onClick={() => void linkTransfer()}
                  >
                    Mark these two as a transfer
                  </QlButton>
                </>
              )}
            </fieldset>

            {patch.isError && (
              <p className={styles.error} role="alert">
                {detailErrorCopy(patch.error)}
              </p>
            )}
            {remove.isError && (
              <p className={styles.error} role="alert" data-testid="delete-error">
                {detailErrorCopy(remove.error)}
              </p>
            )}
            {patch.isSuccess && !patch.isPending && (
              <p className={styles.success} role="status">
                Saved.
              </p>
            )}

            {/* Deleting is permanent and, on a hand-entered row, unrecoverable:
                nothing will fetch it back from a source. So it confirms and says
                what is lost first, the way the account delete on the sibling
                screen does. */}
            {row.manual && confirmingDelete && (
              <div className={styles.stack} data-testid="confirm-delete-transaction">
                <p className={styles.error} role="alert">
                  This is permanent. You entered this row by hand, so no sync and no import will
                  bring it back, and it leaves your ledger, your budgets and your reports at once.
                </p>
                <div className={styles.dialogActions}>
                  <QlButton variant="ghost" onClick={() => setConfirmingDelete(false)}>
                    Keep it
                  </QlButton>
                  <QlButton
                    variant="danger"
                    loading={remove.isPending}
                    onClick={() => remove.mutate(row.id, { onSuccess: onClose })}
                  >
                    Delete it permanently
                  </QlButton>
                </div>
              </div>
            )}

            <div className={styles.dialogActions}>
              {/* Only a hand-entered row can be deleted; a synced one would
                  reappear on the next sync, so the control is not offered. */}
              {row.manual && !confirmingDelete && (
                <QlButton variant="danger" onClick={() => setConfirmingDelete(true)}>
                  Delete this transaction
                </QlButton>
              )}
              <QlButton variant="ghost" onClick={onClose}>
                Close
              </QlButton>
              <QlButton variant="primary" loading={patch.isPending} onClick={save}>
                Save changes
              </QlButton>
            </div>
          </>
        )}
      </div>
    </QlModal>
  )
}
