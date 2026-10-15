/**
 * Budgets overview — this month's period (or the empty state), the household's
 * other periods, and the envelope grid inside whichever period is selected.
 *
 * `GET /v1/budgets/current` 404s when the household has no period for the
 * caller's current month; `@api/budgets` maps that to `null` and this screen
 * renders the "no budget for this month, create one" empty state rather than
 * an error (§202 scope item 5) — while still offering the picker over any
 * OTHER period the household already has, so a 404 this month does not hide a
 * February the household already budgeted.
 *
 * `spent`, `remainingAmount`, `expectedSpend` and `healthPercentage` are all
 * the SERVER's own figures (`EnvelopeHealth`) — nothing here recomputes them
 * from transactions, and nothing sums envelopes into a household total: two
 * envelopes in the same period can hold different currencies, and there is no
 * exchange rate anywhere in this product (owner decision 2).
 */
import { useState } from 'react'
import { QlButton } from '@components/ui/QlButton/QlButton'
import { QlCard } from '@components/ui/QlCard/QlCard'
import { QlInput } from '@components/ui/QlInput/QlInput'
import { QlModal } from '@components/ui/QlModal/QlModal'
import { QlSkeleton } from '@components/ui/QlSkeleton/QlSkeleton'
import { QlTable, type QlTableColumn } from '@components/ui/QlTable/QlTable'
import { EmptyState } from '@components/EmptyState/EmptyState'
import { MoneyDisplay } from '@components/MoneyDisplay/MoneyDisplay'
import { useUserFormat } from '@hooks/useUserFormat'
import { useDialogFocus } from '@hooks/useDialogFocus'
import { useCategories } from '@hooks/useCategories'
import { useCurrentBudget } from '@hooks/useCurrentBudget'
import {
  useBudgetPeriods,
  useCreateBudgetPeriodMutation,
  useDeleteBudgetPeriodMutation,
  useUpdateBudgetPeriodMutation,
} from '@hooks/useBudgetPeriods'
import {
  useCreateEnvelopeMutation,
  useDeleteEnvelopeMutation,
  useEnvelopes,
  useUpdateEnvelopeMutation,
} from '@hooks/useEnvelopes'
import { budgetsErrorCopy } from './errorCopy'
import { BudgetsTabs } from './BudgetsTabs'
import { COUNTRY_PROFILES } from '@api/types'
import type { BudgetPeriodResponse, BudgetType, EnvelopeResponse } from '@api/types'
import styles from './Budgets.module.css'

interface DialogShellProps {
  title: string
  onClose: () => void
  children: React.ReactNode
  testId: string
}

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

/** Amounts are 4-dp decimals; compare them at that precision, not as raw floating-point sums. */
const differs = (a: number, b: number) => Math.round(a * 1e4) !== Math.round(b * 1e4)

function currentMonthInput(): string {
  const now = new Date()
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`
}

const BUDGET_TYPES: BudgetType[] = ['FLEXIBLE', 'ZERO_BASED', 'ROLLOVER']

const BUDGET_TYPE_LABEL: Record<BudgetType, string> = {
  FLEXIBLE: 'Flexible — no constraint on totals',
  ZERO_BASED: 'Zero-based — every dollar allocated',
  ROLLOVER: 'Rollover — unspent balance carries forward',
}

function CreatePeriodDialog({ onClose }: { onClose: () => void }) {
  const create = useCreateBudgetPeriodMutation()
  const [month, setMonth] = useState(currentMonthInput())
  const [totalIncome, setTotalIncome] = useState('')
  const [currency, setCurrency] = useState('')
  const [budgetType, setBudgetType] = useState<BudgetType>('FLEXIBLE')

  function submit(e: React.FormEvent) {
    e.preventDefault()
    create.mutate(
      {
        month: `${month}-01`,
        totalIncome,
        currency: currency ? (currency as BudgetPeriodResponse['currency']) : null,
        budgetType,
      },
      { onSuccess: onClose }
    )
  }

  return (
    <DialogShell title="Open a budget period" onClose={onClose} testId="create-period-dialog">
      <form className={styles.stack} onSubmit={submit}>
        <QlInput
          label="Month"
          type="month"
          value={month}
          onChange={(e) => setMonth(e.target.value)}
          required
        />
        <QlInput
          label="Total income"
          inputMode="decimal"
          value={totalIncome}
          onChange={(e) => setTotalIncome(e.target.value)}
          error={create.error?.fieldError('totalIncome')}
          required
        />
        <label className={styles.selectLabel} htmlFor="period-currency">
          Currency
        </label>
        <select
          id="period-currency"
          className={styles.select}
          value={currency}
          onChange={(e) => setCurrency(e.target.value)}
        >
          <option value="">My reporting currency</option>
          {COUNTRY_PROFILES.map((p) => (
            <option key={p.currency} value={p.currency}>
              {p.currency}
            </option>
          ))}
        </select>
        <label className={styles.selectLabel} htmlFor="period-type">
          Budget type
        </label>
        <select
          id="period-type"
          className={styles.select}
          value={budgetType}
          onChange={(e) => setBudgetType(e.target.value as BudgetType)}
        >
          {BUDGET_TYPES.map((t) => (
            <option key={t} value={t}>
              {BUDGET_TYPE_LABEL[t]}
            </option>
          ))}
        </select>
        {create.isError && !create.error.fieldError('totalIncome') && (
          <p className={styles.error} role="alert">
            {budgetsErrorCopy(create.error)}
          </p>
        )}
        <div className={styles.dialogActions}>
          <QlButton type="button" variant="ghost" onClick={onClose}>
            Cancel
          </QlButton>
          <QlButton type="submit" variant="primary" loading={create.isPending}>
            Open period
          </QlButton>
        </div>
      </form>
    </DialogShell>
  )
}

function EditPeriodDialog({
  period,
  envelopeTotal,
  onClose,
}: {
  period: BudgetPeriodResponse
  envelopeTotal: number
  onClose: () => void
}) {
  const update = useUpdateBudgetPeriodMutation()
  const [totalIncome, setTotalIncome] = useState(period.totalIncome)
  const [budgetType, setBudgetType] = useState<BudgetType>(period.budgetType)

  // A client-side HINT only, for a ZERO_BASED period — never a gate. The server
  // is the sole authority on the invariant (§202 scope item 6); a value that
  // this hint disagrees with is still submitted.
  const zeroBasedMismatch =
    budgetType === 'ZERO_BASED' && differs(Number(totalIncome), envelopeTotal)

  function submit(e: React.FormEvent) {
    e.preventDefault()
    update.mutate({ id: period.id, body: { totalIncome, budgetType } }, { onSuccess: onClose })
  }

  return (
    <DialogShell title="Edit budget period" onClose={onClose} testId="edit-period-dialog">
      <form className={styles.stack} onSubmit={submit}>
        <QlInput
          label="Total income"
          inputMode="decimal"
          value={totalIncome}
          onChange={(e) => setTotalIncome(e.target.value)}
          error={update.error?.fieldError('totalIncome')}
          required
        />
        <label className={styles.selectLabel} htmlFor="edit-period-type">
          Budget type
        </label>
        <select
          id="edit-period-type"
          className={styles.select}
          value={budgetType}
          onChange={(e) => setBudgetType(e.target.value as BudgetType)}
        >
          {BUDGET_TYPES.map((t) => (
            <option key={t} value={t}>
              {BUDGET_TYPE_LABEL[t]}
            </option>
          ))}
        </select>
        {zeroBasedMismatch && (
          <p className={styles.hint} data-testid="zero-based-hint">
            Your {period.currency} envelopes currently allocate {envelopeTotal.toFixed(2)}, not{' '}
            {totalIncome || '0'} — a zero-based period asks for every dollar allocated. (Envelopes
            in another currency aren't counted here — there's no exchange rate to blend them with.)
            You can still save; the server has the final say.
          </p>
        )}
        {update.isError && !update.error.fieldError('totalIncome') && (
          <p className={styles.error} role="alert">
            {budgetsErrorCopy(update.error)}
          </p>
        )}
        <div className={styles.dialogActions}>
          <QlButton type="button" variant="ghost" onClick={onClose}>
            Cancel
          </QlButton>
          <QlButton type="submit" variant="primary" loading={update.isPending}>
            Save
          </QlButton>
        </div>
      </form>
    </DialogShell>
  )
}

function DeletePeriodDialog({ period, onClose }: { period: BudgetPeriodResponse; onClose: () => void }) {
  const remove = useDeleteBudgetPeriodMutation()
  const format = useUserFormat()
  return (
    <DialogShell title={`Delete the ${format.formatDate(period.month)} period?`} onClose={onClose} testId="delete-period-dialog">
      <p className={styles.lede}>
        This removes the period <strong>and every envelope in it</strong>. Your transactions are
        untouched — an envelope is only ever a view of them.
      </p>
      {remove.isError && (
        <p className={styles.error} role="alert">
          {budgetsErrorCopy(remove.error)}
        </p>
      )}
      <div className={styles.dialogActions}>
        <QlButton variant="ghost" onClick={onClose}>
          Keep it
        </QlButton>
        <QlButton variant="danger" loading={remove.isPending} onClick={() => remove.mutate(period.id, { onSuccess: onClose })}>
          Delete period and its envelopes
        </QlButton>
      </div>
    </DialogShell>
  )
}

function CreateEnvelopeDialog({
  period,
  existingCategoryIds,
  envelopeTotal,
  onClose,
}: {
  period: BudgetPeriodResponse
  existingCategoryIds: Set<string>
  envelopeTotal: number
  onClose: () => void
}) {
  const categories = useCategories()
  const create = useCreateEnvelopeMutation(period.id)
  const [categoryId, setCategoryId] = useState('')
  const [allocated, setAllocated] = useState('')

  const available = (categories.data ?? []).filter((c) => !existingCategoryIds.has(c.id))
  const zeroBasedMismatch =
    period.budgetType === 'ZERO_BASED' &&
    allocated !== '' &&
    differs(envelopeTotal + Number(allocated), Number(period.totalIncome))

  function submit(e: React.FormEvent) {
    e.preventDefault()
    create.mutate({ categoryId, allocated }, { onSuccess: onClose })
  }

  return (
    <DialogShell title="Budget a category" onClose={onClose} testId="create-envelope-dialog">
      <form className={styles.stack} onSubmit={submit}>
        <label className={styles.selectLabel} htmlFor="envelope-category">
          Category
        </label>
        <select
          id="envelope-category"
          className={styles.select}
          value={categoryId}
          onChange={(e) => setCategoryId(e.target.value)}
          required
        >
          <option value="" disabled>
            Choose a category
          </option>
          {available.map((c) => (
            <option key={c.id} value={c.id}>
              {c.name}
            </option>
          ))}
        </select>
        <QlInput
          label="Allocated"
          inputMode="decimal"
          value={allocated}
          onChange={(e) => setAllocated(e.target.value)}
          error={create.error?.fieldError('allocated')}
          required
        />
        {zeroBasedMismatch && (
          <p className={styles.hint} data-testid="zero-based-hint">
            This period is zero-based: all envelopes together should add up to{' '}
            {period.totalIncome}. The server has the final say — you can still save.
          </p>
        )}
        {create.isError && !create.error.fieldError('allocated') && !create.error.fieldError('categoryId') && (
          <p className={styles.error} role="alert">
            {budgetsErrorCopy(create.error)}
          </p>
        )}
        <div className={styles.dialogActions}>
          <QlButton type="button" variant="ghost" onClick={onClose}>
            Cancel
          </QlButton>
          <QlButton type="submit" variant="primary" loading={create.isPending}>
            Add envelope
          </QlButton>
        </div>
      </form>
    </DialogShell>
  )
}

function EditEnvelopeDialog({
  period,
  envelope,
  envelopeTotal,
  onClose,
}: {
  period: BudgetPeriodResponse
  envelope: EnvelopeResponse
  envelopeTotal: number
  onClose: () => void
}) {
  const update = useUpdateEnvelopeMutation(period.id)
  const [allocated, setAllocated] = useState(envelope.allocated)

  const otherTotal = envelopeTotal - Number(envelope.allocated)
  const zeroBasedMismatch =
    period.budgetType === 'ZERO_BASED' &&
    allocated !== '' &&
    differs(otherTotal + Number(allocated), Number(period.totalIncome))

  function submit(e: React.FormEvent) {
    e.preventDefault()
    update.mutate({ envelopeId: envelope.id, body: { allocated } }, { onSuccess: onClose })
  }

  return (
    <DialogShell title={`Edit ${envelope.categoryName}`} onClose={onClose} testId="edit-envelope-dialog">
      <form className={styles.stack} onSubmit={submit}>
        <QlInput
          label="Allocated"
          inputMode="decimal"
          value={allocated}
          onChange={(e) => setAllocated(e.target.value)}
          error={update.error?.fieldError('allocated')}
          required
        />
        {zeroBasedMismatch && (
          <p className={styles.hint} data-testid="zero-based-hint">
            This period is zero-based: all envelopes together should add up to{' '}
            {period.totalIncome}. The server has the final say — you can still save.
          </p>
        )}
        {update.isError && !update.error.fieldError('allocated') && (
          <p className={styles.error} role="alert">
            {budgetsErrorCopy(update.error)}
          </p>
        )}
        <div className={styles.dialogActions}>
          <QlButton type="button" variant="ghost" onClick={onClose}>
            Cancel
          </QlButton>
          <QlButton type="submit" variant="primary" loading={update.isPending}>
            Save
          </QlButton>
        </div>
      </form>
    </DialogShell>
  )
}

function DeleteEnvelopeDialog({
  period,
  envelope,
  onClose,
}: {
  period: BudgetPeriodResponse
  envelope: EnvelopeResponse
  onClose: () => void
}) {
  const remove = useDeleteEnvelopeMutation(period.id)
  return (
    <DialogShell title={`Stop budgeting ${envelope.categoryName}?`} onClose={onClose} testId="delete-envelope-dialog">
      <p className={styles.lede}>
        The transactions it aggregated are untouched — this only removes the envelope itself.
      </p>
      {remove.isError && (
        <p className={styles.error} role="alert">
          {budgetsErrorCopy(remove.error)}
        </p>
      )}
      <div className={styles.dialogActions}>
        <QlButton variant="ghost" onClick={onClose}>
          Keep it
        </QlButton>
        <QlButton
          variant="danger"
          loading={remove.isPending}
          onClick={() => remove.mutate(envelope.id, { onSuccess: onClose })}
        >
          Remove
        </QlButton>
      </div>
    </DialogShell>
  )
}

type EnvelopeDialog =
  | { kind: 'create-envelope' }
  | { kind: 'edit-envelope'; envelope: EnvelopeResponse }
  | { kind: 'delete-envelope'; envelope: EnvelopeResponse }
  | { kind: 'edit-period' }
  | { kind: 'delete-period' }
  | { kind: 'create-period' }
  | null

function EnvelopeGrid({ period }: { period: BudgetPeriodResponse }) {
  const envelopes = useEnvelopes(period.id)
  const [dialog, setDialog] = useState<EnvelopeDialog>(null)
  const format = useUserFormat()

  const rows = envelopes.data ?? []
  const existingCategoryIds = new Set(rows.map((e) => e.categoryId))
  // The trivial sum of the ALLOCATION strings, for the client-side zero-based
  // HINT only (§202 scope item 6) — never rendered to the member as a total
  // across currencies. A household can legitimately hold envelopes in more
  // than one currency within the same period (the server treats this as
  // normal, not an error — there is no exchange rate anywhere in this
  // product), so this sum is restricted to envelopes in the PERIOD'S OWN
  // currency; an envelope in a different currency is left out of the hint
  // entirely rather than blended into one number.
  const envelopeTotal = rows
    .filter((e) => e.currency === period.currency)
    .reduce((sum, e) => sum + Number(e.allocated), 0)

  const columns: QlTableColumn<EnvelopeResponse & Record<string, unknown>>[] = [
    { key: 'categoryName', header: 'Category' },
    {
      key: 'allocated',
      header: 'Allocated',
      align: 'right',
      render: (row) => <MoneyDisplay amount={row.allocated} currency={row.currency} colored={false} />,
    },
    {
      key: 'spent',
      header: 'Spent',
      align: 'right',
      render: (row) => <MoneyDisplay amount={row.spent} currency={row.currency} colored={false} />,
    },
    {
      key: 'remaining',
      header: 'Remaining',
      align: 'right',
      render: (row) => (
        <MoneyDisplay amount={row.health.remainingAmount} currency={row.currency} colored={false} />
      ),
    },
    {
      key: 'health',
      header: 'Health',
      render: (row) => (
        <div className={styles.envelopeHealthCell}>
          {/* The word is the information; the colour is decoration only — health
              is never conveyed by colour alone (§202 scope item 9). */}
          <span
            className={styles.envelopeHealthWord}
            style={{ color: row.health.healthPercentage > 100 ? 'var(--red)' : row.health.overPace ? 'var(--amber)' : 'var(--ink-2)' }}
          >
            {row.health.overPace ? 'Over pace' : 'On pace'} · {row.health.healthPercentage}% of budget
          </span>
        </div>
      ),
    },
    {
      key: 'actions',
      header: 'Actions',
      render: (row) => (
        <div className={styles.envelopeActions}>
          <QlButton size="sm" variant="ghost" onClick={() => setDialog({ kind: 'edit-envelope', envelope: row })}>
            Edit
          </QlButton>
          <QlButton size="sm" variant="ghost" onClick={() => setDialog({ kind: 'delete-envelope', envelope: row })}>
            Remove
          </QlButton>
        </div>
      ),
    },
  ]

  return (
    <QlCard>
      <div className={styles.header}>
        <div>
          <h2 className={styles.sectionTitle}>{format.formatDate(period.month)}</h2>
          <p className={styles.hint}>
            Total income{' '}
            <MoneyDisplay amount={period.totalIncome} currency={period.currency} colored={false} /> ·{' '}
            {BUDGET_TYPE_LABEL[period.budgetType]}
          </p>
        </div>
        <div className={styles.headerActions}>
          <QlButton size="sm" variant="ghost" onClick={() => setDialog({ kind: 'edit-period' })}>
            Edit period
          </QlButton>
          <QlButton size="sm" variant="ghost" onClick={() => setDialog({ kind: 'delete-period' })}>
            Delete period
          </QlButton>
          <QlButton size="sm" variant="primary" onClick={() => setDialog({ kind: 'create-envelope' })}>
            Add envelope
          </QlButton>
        </div>
      </div>

      {envelopes.isPending && <QlSkeleton data-testid="envelopes-skeleton" />}
      {envelopes.isError && (
        <p className={styles.error} role="alert">
          {budgetsErrorCopy(envelopes.error)}
        </p>
      )}
      {envelopes.data && rows.length === 0 && (
        <EmptyState title="No envelopes yet">
          <p>Budget a category to start tracking it against this period.</p>
        </EmptyState>
      )}
      {envelopes.data && rows.length > 0 && (
        <div className={styles.tableWrap} data-testid="envelope-grid">
          <QlTable columns={columns} rows={rows as (EnvelopeResponse & Record<string, unknown>)[]} />
        </div>
      )}

      {dialog?.kind === 'create-envelope' && (
        <CreateEnvelopeDialog
          period={period}
          existingCategoryIds={existingCategoryIds}
          envelopeTotal={envelopeTotal}
          onClose={() => setDialog(null)}
        />
      )}
      {dialog?.kind === 'edit-envelope' && (
        <EditEnvelopeDialog
          period={period}
          envelope={dialog.envelope}
          envelopeTotal={envelopeTotal}
          onClose={() => setDialog(null)}
        />
      )}
      {dialog?.kind === 'delete-envelope' && (
        <DeleteEnvelopeDialog period={period} envelope={dialog.envelope} onClose={() => setDialog(null)} />
      )}
      {dialog?.kind === 'edit-period' && (
        <EditPeriodDialog period={period} envelopeTotal={envelopeTotal} onClose={() => setDialog(null)} />
      )}
      {dialog?.kind === 'delete-period' && (
        <DeletePeriodDialog period={period} onClose={() => setDialog(null)} />
      )}
    </QlCard>
  )
}

export default function Budgets() {
  const current = useCurrentBudget()
  const periods = useBudgetPeriods()
  const format = useUserFormat()
  // `null` means "no explicit choice yet" — the picker then falls back to the
  // caller's current-month period below, with no effect needed to sync it:
  // once `current.data` resolves, `effectiveId` reads it directly.
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [showCreate, setShowCreate] = useState(false)

  const effectiveId = selectedId ?? current.data?.id ?? null
  const selectedPeriod =
    (periods.data ?? []).find((p) => p.id === effectiveId) ??
    (current.data && current.data.id === effectiveId ? current.data : null)

  const isLoading = current.isPending || periods.isPending

  return (
    <div className={styles.page}>
      <div className={styles.header}>
        <div>
          <h1 className={styles.heading}>Budgets</h1>
          <p className={styles.lede}>
            What you have planned against what you have spent this month, envelope by envelope.
          </p>
        </div>
        <div className={styles.headerActions}>
          <QlButton variant="primary" onClick={() => setShowCreate(true)}>
            Open a period
          </QlButton>
        </div>
      </div>
      <BudgetsTabs />

      {isLoading && <QlSkeleton data-testid="budgets-skeleton" />}
      {current.isError && (
        <p className={styles.error} role="alert">
          {budgetsErrorCopy(current.error)}
        </p>
      )}
      {periods.isError && (
        <p className={styles.error} role="alert">
          {budgetsErrorCopy(periods.error)}
        </p>
      )}

      {!isLoading && (periods.data ?? []).length > 0 && (
        <div className={styles.periodPicker}>
          <label className={styles.selectLabel} htmlFor="period-select">
            Period
          </label>
          <select
            id="period-select"
            className={styles.select}
            value={effectiveId ?? ''}
            onChange={(e) => setSelectedId(e.target.value)}
          >
            {(periods.data ?? []).map((p) => (
              <option key={p.id} value={p.id}>
                {format.formatDate(p.month)}
                {current.data?.id === p.id ? ' (this month)' : ''}
              </option>
            ))}
          </select>
        </div>
      )}

      {!isLoading &&
        current.isSuccess &&
        periods.isSuccess &&
        !current.data &&
        !selectedPeriod && (
          <QlCard>
            <EmptyState title="No budget for this month" data-testid="no-current-budget">
              <p>Open a period for this month to start allocating envelopes.</p>
            </EmptyState>
          </QlCard>
        )}

      {!isLoading && selectedPeriod && <EnvelopeGrid period={selectedPeriod} />}

      {showCreate && <CreatePeriodDialog onClose={() => setShowCreate(false)} />}
    </div>
  )
}
