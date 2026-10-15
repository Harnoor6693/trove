/**
 * Liabilities — one shared household ledger, CRUD (§4.7, step 203, scope item 3).
 *
 * `currency` is not writable on an edit, for the same reason as an asset's.
 * `interestRate` is a PERCENTAGE, never money — `"19.9900"` means 19.99%.
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
import { useDialogFocus } from '@hooks/useDialogFocus'
import {
  useCreateLiabilityMutation,
  useDeleteLiabilityMutation,
  useLiabilities,
  useUpdateLiabilityMutation,
} from '@hooks/useNetWorth'
import { netWorthErrorCopy } from './errorCopy'
import { COUNTRY_PROFILES } from '@api/types'
import type { LiabilityResponse, LiabilityType } from '@api/types'
import styles from './NetWorth.module.css'

const LIABILITY_TYPES: LiabilityType[] = [
  'MORTGAGE',
  'CAR_LOAN',
  'STUDENT_LOAN',
  'CREDIT_CARD',
  'OTHER',
]
const LIABILITY_TYPE_LABEL: Record<LiabilityType, string> = {
  MORTGAGE: 'Mortgage',
  CAR_LOAN: 'Car loan',
  STUDENT_LOAN: 'Student loan',
  CREDIT_CARD: 'Credit card',
  OTHER: 'Other',
}

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

function CreateLiabilityDialog({ onClose }: { onClose: () => void }) {
  const create = useCreateLiabilityMutation()
  const [name, setName] = useState('')
  const [liabilityType, setLiabilityType] = useState<LiabilityType>('CREDIT_CARD')
  const [balance, setBalance] = useState('')
  const [interestRate, setInterestRate] = useState('')
  const [currency, setCurrency] = useState('')

  function submit(e: React.FormEvent) {
    e.preventDefault()
    create.mutate(
      {
        name,
        liabilityType,
        balance,
        interestRate: interestRate === '' ? null : interestRate,
        currency: currency ? (currency as LiabilityResponse['currency']) : null,
      },
      { onSuccess: onClose }
    )
  }

  return (
    <DialogShell title="Add a liability" onClose={onClose} testId="create-liability-dialog">
      <form className={styles.stack} onSubmit={submit}>
        <QlInput
          label="Name"
          value={name}
          onChange={(e) => setName(e.target.value)}
          error={create.error?.fieldError('name')}
          required
        />
        <label className={styles.selectLabel} htmlFor="liability-type">
          Type
        </label>
        <select
          id="liability-type"
          className={styles.select}
          value={liabilityType}
          onChange={(e) => setLiabilityType(e.target.value as LiabilityType)}
        >
          {LIABILITY_TYPES.map((t) => (
            <option key={t} value={t}>
              {LIABILITY_TYPE_LABEL[t]}
            </option>
          ))}
        </select>
        <QlInput
          label="Balance"
          inputMode="decimal"
          value={balance}
          onChange={(e) => setBalance(e.target.value)}
          error={create.error?.fieldError('balance')}
          required
        />
        <QlInput
          label="Interest rate (%)"
          inputMode="decimal"
          value={interestRate}
          onChange={(e) => setInterestRate(e.target.value)}
          hint="Leave blank for zero-interest debt."
          error={create.error?.fieldError('interestRate')}
        />
        <label className={styles.selectLabel} htmlFor="liability-currency">
          Currency
        </label>
        <select
          id="liability-currency"
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
        {create.isError &&
          !create.error.fieldError('name') &&
          !create.error.fieldError('balance') && (
            <p className={styles.error} role="alert">
              {netWorthErrorCopy(create.error)}
            </p>
          )}
        <div className={styles.dialogActions}>
          <QlButton type="button" variant="ghost" onClick={onClose}>
            Cancel
          </QlButton>
          <QlButton type="submit" variant="primary" loading={create.isPending}>
            Add liability
          </QlButton>
        </div>
      </form>
    </DialogShell>
  )
}

function EditLiabilityDialog({
  liability,
  onClose,
}: {
  liability: LiabilityResponse
  onClose: () => void
}) {
  const update = useUpdateLiabilityMutation()
  const [name, setName] = useState(liability.name)
  const [liabilityType, setLiabilityType] = useState<LiabilityType>(liability.liabilityType)
  const [balance, setBalance] = useState(liability.balance)
  const [interestRate, setInterestRate] = useState(liability.interestRate)

  function submit(e: React.FormEvent) {
    e.preventDefault()
    update.mutate(
      {
        id: liability.id,
        body: {
          name,
          liabilityType,
          balance,
          interestRate: interestRate === '' ? null : interestRate,
          accountId: liability.accountId,
        },
      },
      { onSuccess: onClose }
    )
  }

  return (
    <DialogShell title={`Edit ${liability.name}`} onClose={onClose} testId="edit-liability-dialog">
      <form className={styles.stack} onSubmit={submit}>
        <QlInput
          label="Name"
          value={name}
          onChange={(e) => setName(e.target.value)}
          error={update.error?.fieldError('name')}
          required
        />
        <label className={styles.selectLabel} htmlFor="edit-liability-type">
          Type
        </label>
        <select
          id="edit-liability-type"
          className={styles.select}
          value={liabilityType}
          onChange={(e) => setLiabilityType(e.target.value as LiabilityType)}
        >
          {LIABILITY_TYPES.map((t) => (
            <option key={t} value={t}>
              {LIABILITY_TYPE_LABEL[t]}
            </option>
          ))}
        </select>
        <QlInput
          label="Balance"
          inputMode="decimal"
          value={balance}
          onChange={(e) => setBalance(e.target.value)}
          error={update.error?.fieldError('balance')}
          required
        />
        <QlInput
          label="Interest rate (%)"
          inputMode="decimal"
          value={interestRate}
          onChange={(e) => setInterestRate(e.target.value)}
          error={update.error?.fieldError('interestRate')}
        />
        {update.isError &&
          !update.error.fieldError('name') &&
          !update.error.fieldError('balance') && (
            <p className={styles.error} role="alert">
              {netWorthErrorCopy(update.error)}
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

function DeleteLiabilityDialog({
  liability,
  onClose,
}: {
  liability: LiabilityResponse
  onClose: () => void
}) {
  const remove = useDeleteLiabilityMutation()
  return (
    <DialogShell
      title={`Remove ${liability.name}?`}
      onClose={onClose}
      testId="delete-liability-dialog"
    >
      <p className={styles.lede}>
        This removes the liability from your net-worth ledger. It does not touch any linked account
        or transaction.
      </p>
      {remove.isError && (
        <p className={styles.error} role="alert">
          {netWorthErrorCopy(remove.error)}
        </p>
      )}
      <div className={styles.dialogActions}>
        <QlButton variant="ghost" onClick={onClose}>
          Keep it
        </QlButton>
        <QlButton
          variant="danger"
          loading={remove.isPending}
          onClick={() => remove.mutate(liability.id, { onSuccess: onClose })}
        >
          Remove
        </QlButton>
      </div>
    </DialogShell>
  )
}

type LiabilityDialog =
  | { kind: 'create' }
  | { kind: 'edit'; liability: LiabilityResponse }
  | { kind: 'delete'; liability: LiabilityResponse }
  | null

export function LiabilitiesPanel() {
  const liabilities = useLiabilities()
  const [dialog, setDialog] = useState<LiabilityDialog>(null)
  const rows = liabilities.data ?? []

  const columns: QlTableColumn<LiabilityResponse & Record<string, unknown>>[] = [
    { key: 'name', header: 'Name' },
    {
      key: 'liabilityType',
      header: 'Type',
      render: (row) => LIABILITY_TYPE_LABEL[row.liabilityType],
    },
    {
      key: 'balance',
      header: 'Balance',
      align: 'right',
      render: (row) => (
        <MoneyDisplay amount={row.balance} currency={row.currency} colored={false} />
      ),
    },
    {
      key: 'interestRate',
      header: 'Interest',
      align: 'right',
      render: (row) => `${row.interestRate}%`,
    },
    {
      key: 'actions',
      header: 'Actions',
      render: (row) => (
        <div className={styles.rowActions}>
          <QlButton
            size="sm"
            variant="ghost"
            onClick={() => setDialog({ kind: 'edit', liability: row })}
          >
            Edit
          </QlButton>
          <QlButton
            size="sm"
            variant="ghost"
            onClick={() => setDialog({ kind: 'delete', liability: row })}
          >
            Remove
          </QlButton>
        </div>
      ),
    },
  ]

  return (
    <QlCard>
      <div className={styles.header}>
        <h2 className={styles.sectionTitle}>Liabilities</h2>
        <QlButton size="sm" variant="primary" onClick={() => setDialog({ kind: 'create' })}>
          Add liability
        </QlButton>
      </div>

      {liabilities.isPending && <QlSkeleton data-testid="liabilities-skeleton" />}
      {liabilities.isError && (
        <p className={styles.error} role="alert">
          {netWorthErrorCopy(liabilities.error)}
        </p>
      )}
      {liabilities.data && rows.length === 0 && (
        <EmptyState title="No liabilities yet">
          <p>Add a mortgage, a loan or a credit card balance.</p>
        </EmptyState>
      )}
      {liabilities.data && rows.length > 0 && (
        <div className={styles.tableWrap} data-testid="liabilities-table">
          <QlTable
            columns={columns}
            rows={rows as (LiabilityResponse & Record<string, unknown>)[]}
          />
        </div>
      )}

      {dialog?.kind === 'create' && <CreateLiabilityDialog onClose={() => setDialog(null)} />}
      {dialog?.kind === 'edit' && (
        <EditLiabilityDialog liability={dialog.liability} onClose={() => setDialog(null)} />
      )}
      {dialog?.kind === 'delete' && (
        <DeleteLiabilityDialog liability={dialog.liability} onClose={() => setDialog(null)} />
      )}
    </QlCard>
  )
}
