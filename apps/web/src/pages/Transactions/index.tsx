/**
 * Transactions — the household ledger: one page of it, filtered, with the pager
 * the server's own `meta` drives.
 *
 * This replaced a twenty-line placeholder that rendered nothing rather than a
 * fixture, because *no number that could be mistaken for the user's money may
 * originate anywhere but the API*. What it replaces it WITH is a page that
 * cannot quietly hide part of the ledger:
 *
 * `GET /v1/transactions` is paged, and its 200 requires both `data` and `meta`.
 * The shipped API layer returned `Promise<BankTransaction[]>` and dropped `meta`,
 * so the screen would have shown the server's first 50 rows and had no idea the
 * other rows existed — a silent truncation of the member's own ledger. The list
 * consumes a `Page<T>` now, the pager reads `meta.total / page / limit`, and
 * nothing on this page invents a count from `rows.length`.
 *
 * Filters are exactly the ones `listTransactions` declares — none invented, none
 * silently dropped — and every one of them travels in the query string, so a
 * filtered view is a filtered REQUEST rather than a client-side slice of one
 * page.
 */
import { useMemo, useState } from 'react'
import { QlButton } from '@components/ui/QlButton/QlButton'
import { QlCard } from '@components/ui/QlCard/QlCard'
import { QlBadge } from '@components/ui/QlBadge/QlBadge'
import { QlInput } from '@components/ui/QlInput/QlInput'
import { QlModal } from '@components/ui/QlModal/QlModal'
import { QlSkeleton } from '@components/ui/QlSkeleton/QlSkeleton'
import { EmptyState } from '@components/EmptyState/EmptyState'
import { MoneyDisplay } from '@components/MoneyDisplay/MoneyDisplay'
import { useUserFormat } from '@hooks/useUserFormat'
import { useDialogFocus } from '@hooks/useDialogFocus'
import { useAccounts } from '@hooks/useAccounts'
import { useCategories } from '@hooks/useCategories'
import {
  useCreateManualTransactionMutation,
  usePatchTransactionMutation,
  useTransactions,
} from '@hooks/useTransactions'
import { TransactionDetail } from './TransactionDetail'
import { detailErrorCopy } from './errorCopy'
import { isDirectional, signedAmount } from '@api/transactionDirection'
import type { ApiError } from '@api/client'
import type { TransactionResponse, TransactionType, TransactionsQuery } from '@api/types'
import styles from './Transactions.module.css'

/** Rows per page. Well inside the operation's maximum of 500. */
const PAGE_SIZE = 25

/** Exactly the four values the operation's `sort` allowlist accepts. */
const SORTS = [
  { value: 'date,desc', label: 'Newest first' },
  { value: 'date,asc', label: 'Oldest first' },
  { value: 'amount,desc', label: 'Largest amount first' },
  { value: 'amount,asc', label: 'Smallest amount first' },
] as const

/** All seven the contract declares — a two-value union would drop five of them. */
const TYPES: TransactionType[] = [
  'DEBIT',
  'CREDIT',
  'TRANSFER',
  'FEE',
  'INTEREST',
  'REFUND',
  'OTHER',
]

/**
 * The word beside an amount whose direction the contract does not state
 * (`INTEREST` is "earned or charged", a `TRANSFER` leg is either side, `OTHER`
 * is unmapped). The magnitude alone would be ambiguous, so the type is written
 * out rather than a sign being guessed.
 */
const TYPE_LABEL: Record<TransactionType, string> = {
  DEBIT: 'Out',
  CREDIT: 'In',
  TRANSFER: 'Transfer leg',
  FEE: 'Fee',
  INTEREST: 'Interest',
  REFUND: 'Refund',
  OTHER: 'Unclassified',
}

function listErrorCopy(error: ApiError): string {
  switch (error.code) {
    case 'validation-error':
      return (
        error.fieldErrors[0]?.message ??
        error.detail ??
        'One of those filters was refused by the server.'
      )
    case 'rate-limited':
      return 'Too many requests just now. Wait a few seconds, then try again.'
    default:
      return error.isNetworkError
        ? 'Trove could not reach the server. Check your connection and try again.'
        : 'Your transactions could not be loaded.'
  }
}

function manualErrorCopy(error: ApiError): string {
  switch (error.code) {
    case 'conflict':
      return (
        error.detail ??
        'Transactions can only be entered by hand on a manual account. Add one from Accounts first.'
      )
    case 'not-found':
      return 'That account is not in this household any more.'
    case 'validation-error':
      return error.detail ?? 'Some of those details were refused.'
    case 'rate-limited':
      return 'Too many requests just now. Wait a few seconds, then try again.'
    default:
      return 'That transaction was not recorded.'
  }
}

/* ---------------------------------------------------------- manual entry */

function ManualEntryDialog({ onClose }: { onClose: () => void }) {
  const accounts = useAccounts()
  const categories = useCategories()
  const create = useCreateManualTransactionMutation()
  const ref = useDialogFocus<HTMLDivElement>(true)

  // `createManualTransaction` only accepts an account whose sourceKind is
  // MANUAL — anything else is a 409. The picker offers only those, so the
  // member is never walked into a refusal the client could have prevented.
  const manualAccounts = (accounts.data ?? []).filter((a) => a.sourceKind === 'MANUAL')

  const [accountId, setAccountId] = useState('')
  const [amount, setAmount] = useState('')
  const [date, setDate] = useState('')
  const [type, setType] = useState<TransactionType>('DEBIT')
  const [merchant, setMerchant] = useState('')
  const [categoryId, setCategoryId] = useState('')
  const [notes, setNotes] = useState('')

  const chosen = manualAccounts.find((a) => a.id === accountId) ?? manualAccounts[0]

  return (
    <QlModal isOpen onClose={onClose} title="Record a transaction by hand">
      <div ref={ref} tabIndex={-1} className={styles.stack} data-testid="manual-entry-dialog">
        {manualAccounts.length === 0 ? (
          /* The empty case is explained rather than left to the server's 400. */
          <EmptyState title="You need a manual account first" data-testid="no-manual-accounts">
            <p>
              Cash, cheques and anything no bank will ever report go on a manual account — the
              credential-less kind. Add one from Accounts, then come back here.
            </p>
          </EmptyState>
        ) : (
          <>
            <label className={styles.selectLabel} htmlFor="manual-account">
              Account
            </label>
            <select
              id="manual-account"
              className={styles.select}
              value={chosen?.id ?? ''}
              onChange={(e) => setAccountId(e.target.value)}
            >
              {manualAccounts.map((a) => (
                <option key={a.id} value={a.id}>
                  {a.displayName} · {a.currency}
                </option>
              ))}
            </select>

            <QlInput
              label="Amount"
              inputMode="decimal"
              value={amount}
              onChange={(e) => setAmount(e.target.value)}
              hint="The amount as a positive figure — whether it went out or came in is the type below."
              error={create.error?.fieldError('amount')}
            />
            <QlInput
              label="Date"
              type="date"
              value={date}
              onChange={(e) => setDate(e.target.value)}
              error={create.error?.fieldError('date')}
            />

            <label className={styles.selectLabel} htmlFor="manual-type">
              Type
            </label>
            <select
              id="manual-type"
              className={styles.select}
              value={type}
              onChange={(e) => setType(e.target.value as TransactionType)}
            >
              {TYPES.map((t) => (
                <option key={t} value={t}>
                  {t}
                </option>
              ))}
            </select>

            <QlInput
              label="Merchant (optional)"
              value={merchant}
              onChange={(e) => setMerchant(e.target.value)}
            />

            <label className={styles.selectLabel} htmlFor="manual-category">
              Category (optional)
            </label>
            <select
              id="manual-category"
              className={styles.select}
              value={categoryId}
              onChange={(e) => setCategoryId(e.target.value)}
            >
              <option value="">Let Trove decide</option>
              {(categories.data ?? []).map((c) => (
                <option key={c.id} value={c.id}>
                  {c.name}
                </option>
              ))}
            </select>

            <QlInput
              label="Notes (optional)"
              value={notes}
              onChange={(e) => setNotes(e.target.value)}
            />

            {/* The currency is the account's and the operation does not accept
                one, so it is stated rather than asked for. */}
            {chosen && (
              <p className={styles.hint}>
                This will be recorded in {chosen.currency}, the currency {chosen.displayName} is
                held in.
              </p>
            )}

            {create.isError && (
              <p className={styles.error} role="alert" data-testid="manual-error">
                {manualErrorCopy(create.error)}
              </p>
            )}
          </>
        )}

        <div className={styles.dialogActions}>
          <QlButton variant="ghost" onClick={onClose}>
            {manualAccounts.length === 0 ? 'Close' : 'Cancel'}
          </QlButton>
          {manualAccounts.length > 0 && (
            <QlButton
              variant="primary"
              loading={create.isPending}
              disabled={!chosen || amount.trim() === '' || date === ''}
              onClick={() =>
                chosen &&
                create.mutate(
                  {
                    accountId: chosen.id,
                    amount: amount.trim(),
                    date,
                    type,
                    merchant: merchant.trim() === '' ? null : merchant.trim(),
                    categoryId: categoryId === '' ? null : categoryId,
                    notes: notes.trim() === '' ? null : notes.trim(),
                  },
                  { onSuccess: onClose }
                )
              }
            >
              Record it
            </QlButton>
          )}
        </div>
      </div>
    </QlModal>
  )
}

/* ---------------------------------------------------------------- page */

export default function Transactions() {
  const [query, setQuery] = useState<TransactionsQuery>({
    page: 0,
    limit: PAGE_SIZE,
    sort: 'date,desc',
  })
  const [openId, setOpenId] = useState<string | null>(null)
  const [manualOpen, setManualOpen] = useState(false)

  const page = useTransactions(query)
  const accounts = useAccounts()
  const categories = useCategories()
  const patch = usePatchTransactionMutation()
  const format = useUserFormat()

  const categoryName = useMemo(
    () => new Map((categories.data ?? []).map((c) => [c.id, c.name])),
    [categories.data]
  )
  const accountName = useMemo(
    () => new Map((accounts.data ?? []).map((a) => [a.id, a.displayName])),
    [accounts.data]
  )

  /** Every filter change goes back to page zero: page 4 of a new filter is a lie. */
  function setFilter(patchQuery: Partial<TransactionsQuery>) {
    setQuery((prev) => ({ ...prev, ...patchQuery, page: 0 }))
  }

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
          <h1 className={styles.heading}>Transactions</h1>
          <p className={styles.lede}>
            Your household&rsquo;s shared ledger. Each row is shown in the currency its account is
            held in.
          </p>
        </div>
        <QlButton variant="primary" onClick={() => setManualOpen(true)}>
          Record one by hand
        </QlButton>
      </header>

      <QlCard>
        <div className={styles.filters} data-testid="transaction-filters">
          <QlInput
            label="Search"
            value={query.q ?? ''}
            onChange={(e) => setFilter({ q: e.target.value })}
            hint="Matches merchant, description and notes."
          />
          <div className={styles.filterField}>
            <label className={styles.selectLabel} htmlFor="filter-account">
              Account
            </label>
            <select
              id="filter-account"
              className={styles.select}
              value={query.accountId ?? ''}
              onChange={(e) => setFilter({ accountId: e.target.value || undefined })}
            >
              <option value="">All accounts</option>
              {(accounts.data ?? []).map((a) => (
                <option key={a.id} value={a.id}>
                  {a.displayName}
                </option>
              ))}
            </select>
          </div>
          <div className={styles.filterField}>
            <label className={styles.selectLabel} htmlFor="filter-category">
              Category
            </label>
            <select
              id="filter-category"
              className={styles.select}
              value={query.categoryId ?? ''}
              onChange={(e) => setFilter({ categoryId: e.target.value || undefined })}
            >
              <option value="">All categories</option>
              {(categories.data ?? []).map((c) => (
                <option key={c.id} value={c.id}>
                  {c.name}
                </option>
              ))}
            </select>
          </div>
          <div className={styles.filterField}>
            <label className={styles.selectLabel} htmlFor="filter-type">
              Type
            </label>
            <select
              id="filter-type"
              className={styles.select}
              value={query.type ?? ''}
              onChange={(e) =>
                setFilter({ type: (e.target.value || undefined) as TransactionType | undefined })
              }
            >
              <option value="">All types</option>
              {TYPES.map((t) => (
                <option key={t} value={t}>
                  {t}
                </option>
              ))}
            </select>
          </div>
          <QlInput
            label="From"
            type="date"
            value={query.from ?? ''}
            onChange={(e) => setFilter({ from: e.target.value || undefined })}
          />
          <QlInput
            label="To"
            type="date"
            value={query.to ?? ''}
            onChange={(e) => setFilter({ to: e.target.value || undefined })}
          />
          <div className={styles.filterField}>
            <label className={styles.selectLabel} htmlFor="filter-sort">
              Order
            </label>
            <select
              id="filter-sort"
              className={styles.select}
              value={query.sort ?? 'date,desc'}
              onChange={(e) => setFilter({ sort: e.target.value as TransactionsQuery['sort'] })}
            >
              {SORTS.map((s) => (
                <option key={s.value} value={s.value}>
                  {s.label}
                </option>
              ))}
            </select>
          </div>
        </div>
      </QlCard>

      {/* The inline category select is a real write, so its failure is a real
          message. Without this the member picked a category, watched the select
          snap back on the next render and was told nothing — a 400, 404, 409,
          403 or 429 all landed in silence. The copy is the detail view's own
          slug switch, so one operation does not get two vocabularies. */}
      {patch.isError && (
        <p className={styles.error} role="alert" data-testid="inline-category-error">
          {detailErrorCopy(patch.error)}
        </p>
      )}

      {page.isPending && (
        <div role="status">
          <QlSkeleton variant="row" rows={5} data-testid="transactions-skeleton" />
          <span className={styles.srOnly}>Loading your transactions</span>
        </div>
      )}

      {page.isError && (
        <QlCard>
          <div className={styles.stack} data-testid="transactions-error">
            <p className={styles.error} role="alert">
              {listErrorCopy(page.error)}
            </p>
            {page.error.status === 429 && (
              <p className={styles.hint}>
                Trove is asking you to slow down, not refusing you — your session is fine.
              </p>
            )}
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
          <EmptyState title="Nothing matches those filters" data-testid="transactions-empty">
            <p>
              Widen the date range, clear the search, or connect an account from Accounts if there
              is nothing here yet.
            </p>
          </EmptyState>
        </QlCard>
      )}

      {page.data && rows.length > 0 && (
        <>
          <QlCard padding="none">
            <div className={styles.tableWrap}>
              <table className={styles.table} data-testid="transactions-table">
                <caption className={styles.caption}>
                  Showing {firstShown}&ndash;{lastShown} of {total} transactions
                </caption>
                <thead>
                  <tr>
                    <th scope="col">Date</th>
                    <th scope="col">Description</th>
                    <th scope="col">Account</th>
                    <th scope="col">Category</th>
                    <th scope="col" className={styles.right}>
                      Amount
                    </th>
                    <th scope="col">
                      <span className={styles.srOnly}>Actions</span>
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map((t: TransactionResponse) => (
                    <tr key={t.id} data-testid={`txn-row-${t.id}`}>
                      <td>{format.formatDate(t.date)}</td>
                      <td>
                        {t.merchantName ?? t.description ?? '—'}
                        {t.pending && <QlBadge variant="amber">Pending</QlBadge>}
                        {t.transferGroupId && <QlBadge variant="blue">Transfer</QlBadge>}
                        {t.manual && <QlBadge variant="neutral">By hand</QlBadge>}
                      </td>
                      <td>{accountName.get(t.accountId) ?? '—'}</td>
                      <td>
                        {/* Inline category assignment: one select, one PATCH,
                            one key in the body. */}
                        <select
                          className={styles.select}
                          aria-label={`Category for ${t.merchantName ?? t.description ?? t.id}`}
                          value={t.categoryId ?? ''}
                          onChange={(e) =>
                            patch.mutate({
                              id: t.id,
                              patch: { categoryId: e.target.value === '' ? null : e.target.value },
                            })
                          }
                        >
                          <option value="">Uncategorised</option>
                          {(categories.data ?? []).map((c) => (
                            <option key={c.id} value={c.id}>
                              {c.name}
                            </option>
                          ))}
                        </select>
                        {t.categoryId && !categoryName.has(t.categoryId) && (
                          <span className={styles.hint}> (category not loaded)</span>
                        )}
                      </td>
                      <td className={styles.right}>
                        {/* The server sends a positive magnitude and a type; the
                            direction is read off the type by
                            `@api/transactionDirection`, which follows the
                            contract instead of "CREDIT, or else negative" — the
                            rule that rendered a REFUND as more money leaving.
                            A type the contract gives no direction to is shown
                            unsigned, untinted, with the type spelled out. */}
                        <MoneyDisplay
                          amount={signedAmount(t)}
                          currency={t.currency}
                          colored={isDirectional(t.type)}
                        />
                        {!isDirectional(t.type) && (
                          <span className={styles.hint}> {TYPE_LABEL[t.type]}</span>
                        )}
                      </td>
                      <td>
                        <QlButton variant="ghost" onClick={() => setOpenId(t.id)}>
                          Details for {t.merchantName ?? t.description ?? t.id}
                        </QlButton>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </QlCard>

          {/* The pager is driven entirely by the server's `meta`. */}
          <nav className={styles.pager} aria-label="Transaction pages" data-testid="transactions-pager">
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

      {/* The detail view finds its own transfer candidates: the other leg of a
          transfer is on a different account, so this page's filtered, paged rows
          are exactly the wrong set to offer. */}
      {openId !== null && <TransactionDetail id={openId} onClose={() => setOpenId(null)} />}
      {manualOpen && <ManualEntryDialog onClose={() => setManualOpen(false)} />}
    </div>
  )
}
