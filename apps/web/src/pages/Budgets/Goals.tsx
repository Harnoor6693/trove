/**
 * Savings goals — list, create, edit and delete, with progress rendered from
 * exactly the fields `GoalResponse` carries.
 *
 * `achieved` is derived server-side from `currentAmount >= targetAmount` and
 * is never sent on a write; `currency` is fixed at creation. There is no
 * progress PERCENTAGE in the response beyond the trivial
 * `currentAmount`/`targetAmount` ratio this screen renders from the two
 * strings — nothing here invents a figure the server did not send.
 */
import { useState } from 'react'
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
import {
  useCreateGoalMutation,
  useDeleteGoalMutation,
  useGoals,
  useUpdateGoalMutation,
} from '@hooks/useGoals'
import { budgetsErrorCopy } from './errorCopy'
import { BudgetsTabs } from './BudgetsTabs'
import { COUNTRY_PROFILES } from '@api/types'
import type { GoalResponse } from '@api/types'
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

function CreateGoalDialog({ onClose }: { onClose: () => void }) {
  const create = useCreateGoalMutation()
  const [name, setName] = useState('')
  const [targetAmount, setTargetAmount] = useState('')
  const [currency, setCurrency] = useState('')
  const [deadline, setDeadline] = useState('')

  function submit(e: React.FormEvent) {
    e.preventDefault()
    create.mutate(
      {
        name,
        targetAmount,
        currency: currency ? (currency as GoalResponse['currency']) : null,
        deadline: deadline || null,
      },
      { onSuccess: onClose }
    )
  }

  return (
    <DialogShell title="Start a savings goal" onClose={onClose} testId="create-goal-dialog">
      <form className={styles.stack} onSubmit={submit}>
        <QlInput
          label="Name"
          value={name}
          onChange={(e) => setName(e.target.value)}
          error={create.error?.fieldError('name')}
          required
        />
        <QlInput
          label="Target amount"
          inputMode="decimal"
          value={targetAmount}
          onChange={(e) => setTargetAmount(e.target.value)}
          error={create.error?.fieldError('targetAmount')}
          required
        />
        <label className={styles.selectLabel} htmlFor="goal-currency">
          Currency
        </label>
        <select
          id="goal-currency"
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
        <QlInput
          label="Target date (optional)"
          type="date"
          value={deadline}
          onChange={(e) => setDeadline(e.target.value)}
        />
        {create.isError &&
          !create.error.fieldError('name') &&
          !create.error.fieldError('targetAmount') && (
            <p className={styles.error} role="alert">
              {budgetsErrorCopy(create.error)}
            </p>
          )}
        <div className={styles.dialogActions}>
          <QlButton type="button" variant="ghost" onClick={onClose}>
            Cancel
          </QlButton>
          <QlButton type="submit" variant="primary" loading={create.isPending}>
            Start goal
          </QlButton>
        </div>
      </form>
    </DialogShell>
  )
}

function EditGoalDialog({ goal, onClose }: { goal: GoalResponse; onClose: () => void }) {
  const update = useUpdateGoalMutation()
  const [name, setName] = useState(goal.name)
  const [targetAmount, setTargetAmount] = useState(goal.targetAmount)
  const [currentAmount, setCurrentAmount] = useState(goal.currentAmount)
  const [deadline, setDeadline] = useState(goal.deadline ?? '')

  function submit(e: React.FormEvent) {
    e.preventDefault()
    update.mutate(
      { id: goal.id, body: { name, targetAmount, currentAmount, deadline: deadline || null } },
      { onSuccess: onClose }
    )
  }

  return (
    <DialogShell title={`Update ${goal.name}`} onClose={onClose} testId="edit-goal-dialog">
      <form className={styles.stack} onSubmit={submit}>
        <QlInput
          label="Name"
          value={name}
          onChange={(e) => setName(e.target.value)}
          error={update.error?.fieldError('name')}
          required
        />
        <QlInput
          label="Target amount"
          inputMode="decimal"
          value={targetAmount}
          onChange={(e) => setTargetAmount(e.target.value)}
          error={update.error?.fieldError('targetAmount')}
          required
        />
        <QlInput
          label="Saved so far"
          inputMode="decimal"
          value={currentAmount}
          onChange={(e) => setCurrentAmount(e.target.value)}
          error={update.error?.fieldError('currentAmount')}
          hint="A contribution — record it here as the new total saved, not the amount you just added."
          required
        />
        <QlInput
          label="Target date (optional)"
          type="date"
          value={deadline}
          onChange={(e) => setDeadline(e.target.value)}
        />
        {update.isError &&
          !update.error.fieldError('name') &&
          !update.error.fieldError('targetAmount') &&
          !update.error.fieldError('currentAmount') && (
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

function DeleteGoalDialog({ goal, onClose }: { goal: GoalResponse; onClose: () => void }) {
  const remove = useDeleteGoalMutation()
  return (
    <DialogShell title={`Delete ${goal.name}?`} onClose={onClose} testId="delete-goal-dialog">
      <p className={styles.lede}>This only removes the goal itself; nothing else changes.</p>
      {remove.isError && (
        <p className={styles.error} role="alert">
          {budgetsErrorCopy(remove.error)}
        </p>
      )}
      <div className={styles.dialogActions}>
        <QlButton variant="ghost" onClick={onClose}>
          Keep it
        </QlButton>
        <QlButton variant="danger" loading={remove.isPending} onClick={() => remove.mutate(goal.id, { onSuccess: onClose })}>
          Delete
        </QlButton>
      </div>
    </DialogShell>
  )
}

function GoalRow({ goal, onEdit, onDelete }: { goal: GoalResponse; onEdit: () => void; onDelete: () => void }) {
  const format = useUserFormat()

  return (
    <li className={styles.goalRow} data-testid={`goal-row-${goal.id}`}>
      <div className={styles.goalHeader}>
        <div>
          <p className={styles.goalName}>{goal.name}</p>
          {goal.deadline && <p className={styles.goalMeta}>By {format.formatDate(goal.deadline)}</p>}
        </div>
        {/* `achieved` is the one progress signal `GoalResponse` actually carries
            (server-derived from `currentAmount >= targetAmount`) — there is no
            percentage field on this response, so none is invented here (§202
            scope item 7). */}
        <QlBadge variant={goal.achieved ? 'sage' : 'neutral'}>
          {goal.achieved ? 'Achieved' : 'In progress'}
        </QlBadge>
      </div>
      <div className={styles.goalAmounts}>
        <MoneyDisplay amount={goal.currentAmount} currency={goal.currency} colored={false} />
        <span>of</span>
        <MoneyDisplay amount={goal.targetAmount} currency={goal.currency} colored={false} />
      </div>
      <div className={styles.goalActions}>
        <QlButton size="sm" variant="ghost" onClick={onEdit}>
          Contribute / edit
        </QlButton>
        <QlButton size="sm" variant="ghost" onClick={onDelete}>
          Delete
        </QlButton>
      </div>
    </li>
  )
}

export default function Goals() {
  const q = useGoals()
  const [dialog, setDialog] = useState<
    { kind: 'create' } | { kind: 'edit'; goal: GoalResponse } | { kind: 'delete'; goal: GoalResponse } | null
  >(null)

  return (
    <div className={styles.page}>
      <div className={styles.header}>
        <div>
          <h1 className={styles.heading}>Budgets</h1>
          <p className={styles.lede}>Savings goals your household is working toward.</p>
        </div>
        <div className={styles.headerActions}>
          <QlButton variant="primary" onClick={() => setDialog({ kind: 'create' })}>
            Start a goal
          </QlButton>
        </div>
      </div>
      <BudgetsTabs />

      <QlCard>
        {q.isPending && <QlSkeleton data-testid="goals-skeleton" />}
        {q.isError && (
          <p className={styles.error} role="alert">
            {budgetsErrorCopy(q.error)}
          </p>
        )}
        {q.data && q.data.length === 0 && (
          <EmptyState title="No savings goals yet">
            <p>Start one to track progress toward something specific.</p>
          </EmptyState>
        )}
        {q.data && q.data.length > 0 && (
          <ul className={styles.goalList} data-testid="goal-list">
            {q.data.map((goal) => (
              <GoalRow
                key={goal.id}
                goal={goal}
                onEdit={() => setDialog({ kind: 'edit', goal })}
                onDelete={() => setDialog({ kind: 'delete', goal })}
              />
            ))}
          </ul>
        )}
      </QlCard>

      {dialog?.kind === 'create' && <CreateGoalDialog onClose={() => setDialog(null)} />}
      {dialog?.kind === 'edit' && <EditGoalDialog goal={dialog.goal} onClose={() => setDialog(null)} />}
      {dialog?.kind === 'delete' && <DeleteGoalDialog goal={dialog.goal} onClose={() => setDialog(null)} />}
    </div>
  )
}
