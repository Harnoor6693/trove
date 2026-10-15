/**
 * Merchant rules — the household's own `RULE`-sourced merchant mappings, in
 * priority order, and the "test this rule" dry-run preview.
 *
 * `listCategoryRules` returns them already in priority order (`priority`
 * ascending, then creation order); the reorder control is real move-up/
 * move-down buttons rather than drag-only, because a drag-only list is
 * unusable from the keyboard (§202 scope item 4 — this app's accessibility
 * floor). `reorderCategoryRules` takes the FULL ordered id list, so every move
 * sends the whole list, not a delta.
 *
 * The dry-run preview writes nothing — it is wired as a mutation whose
 * "variables" are the merchant string being tested, called explicitly by a
 * button press, never on every keystroke.
 */
import { useState } from 'react'
import { QlButton } from '@components/ui/QlButton/QlButton'
import { QlCard } from '@components/ui/QlCard/QlCard'
import { QlBadge } from '@components/ui/QlBadge/QlBadge'
import { QlInput } from '@components/ui/QlInput/QlInput'
import { QlModal } from '@components/ui/QlModal/QlModal'
import { QlSkeleton } from '@components/ui/QlSkeleton/QlSkeleton'
import { useDialogFocus } from '@hooks/useDialogFocus'
import { useCategories } from '@hooks/useCategories'
import {
  useCategoryRules,
  useCreateCategoryRuleMutation,
  useDeleteCategoryRuleMutation,
  useDryRunCategoryRuleMutation,
  useReorderCategoryRulesMutation,
  useUpdateCategoryRuleMutation,
} from '@hooks/useCategoryRules'
import { budgetsErrorCopy } from './errorCopy'
import { BudgetsTabs } from './BudgetsTabs'
import type { CategoryRuleResponse, MatchType } from '@api/types'
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

/** "Test this rule" — a preview against the real pipeline, before anything is saved. */
function DryRunTester() {
  const [merchant, setMerchant] = useState('')
  const dryRun = useDryRunCategoryRuleMutation()

  return (
    <div className={styles.stack} data-testid="dry-run-tester">
      <h2 className={styles.sectionTitle}>Test a merchant string</h2>
      <p className={styles.hint}>
        See what a merchant would resolve to right now, against your own rules and the built-in
        defaults — before you save anything.
      </p>
      <form
        className={styles.periodPicker}
        onSubmit={(e) => {
          e.preventDefault()
          if (merchant.trim()) dryRun.mutate({ merchant })
        }}
      >
        <QlInput
          aria-label="Merchant to test"
          placeholder="e.g. STARBUCKS #4471"
          value={merchant}
          onChange={(e) => setMerchant(e.target.value)}
        />
        <QlButton type="submit" variant="ghost" loading={dryRun.isPending}>
          Test
        </QlButton>
      </form>
      {dryRun.isError && (
        <p className={styles.error} role="alert">
          {budgetsErrorCopy(dryRun.error)}
        </p>
      )}
      {dryRun.isSuccess && (
        <div className={styles.dryRunResult} role="status" data-testid="dry-run-result">
          <span>
            Would resolve to <strong>{dryRun.data.categoryName}</strong>
          </span>
          <span className={styles.hint}>
            Match confidence {dryRun.data.confidence}
            {dryRun.data.source ? ` · from one of your ${dryRun.data.source.toLowerCase()} rules` : ' · from the built-in defaults'}
          </span>
        </div>
      )}
    </div>
  )
}

interface RuleFormProps {
  initial?: CategoryRuleResponse
  onClose: () => void
}

function RuleFormDialog({ initial, onClose }: RuleFormProps) {
  const categories = useCategories()
  const create = useCreateCategoryRuleMutation()
  const update = useUpdateCategoryRuleMutation()
  const dryRun = useDryRunCategoryRuleMutation()
  const [merchant, setMerchant] = useState(initial?.merchant ?? '')
  const [matchType, setMatchType] = useState<MatchType>(initial?.matchType ?? 'EXACT')
  const [categoryId, setCategoryId] = useState(initial?.categoryId ?? '')
  const mutation = initial ? update : create

  function submit(e: React.FormEvent) {
    e.preventDefault()
    const body = { merchant, matchType, categoryId }
    if (initial) {
      update.mutate({ id: initial.id, body }, { onSuccess: onClose })
    } else {
      create.mutate(body, { onSuccess: onClose })
    }
  }

  return (
    <DialogShell
      title={initial ? 'Edit merchant rule' : 'Add a merchant rule'}
      onClose={onClose}
      testId="rule-form-dialog"
    >
      <form className={styles.stack} onSubmit={submit}>
        <QlInput
          label="Merchant"
          value={merchant}
          onChange={(e) => setMerchant(e.target.value)}
          error={mutation.error?.fieldError('merchant')}
          hint="A full name for an exact match, or a leading fragment for a prefix match."
          required
        />
        <fieldset style={{ border: 0, padding: 0, margin: 0 }}>
          <legend className={styles.selectLabel}>Match type</legend>
          {(['EXACT', 'PREFIX'] as const).map((mt) => (
            <label
              key={mt}
              style={{ display: 'flex', gap: 'var(--space-2)', alignItems: 'center', marginTop: 'var(--space-2)' }}
            >
              <input
                type="radio"
                name="matchType"
                value={mt}
                checked={matchType === mt}
                onChange={() => setMatchType(mt)}
              />
              <span>{mt === 'EXACT' ? 'Exact match' : 'Starts with'}</span>
            </label>
          ))}
        </fieldset>
        <label className={styles.selectLabel} htmlFor="rule-category">
          Category
        </label>
        <select
          id="rule-category"
          className={styles.select}
          value={categoryId}
          onChange={(e) => setCategoryId(e.target.value)}
          required
        >
          <option value="" disabled>
            Choose a category
          </option>
          {(categories.data ?? []).map((c) => (
            <option key={c.id} value={c.id}>
              {c.name}
            </option>
          ))}
        </select>
        {mutation.error?.fieldError('categoryId') && (
          <p className={styles.error} role="alert">
            {mutation.error.fieldError('categoryId')}
          </p>
        )}
        <div>
          <QlButton
            type="button"
            variant="ghost"
            size="sm"
            disabled={!merchant.trim()}
            loading={dryRun.isPending}
            onClick={() => dryRun.mutate({ merchant })}
          >
            Test this pattern first
          </QlButton>
          {dryRun.isSuccess && (
            <p className={styles.hint} role="status">
              Would currently resolve to <strong>{dryRun.data.categoryName}</strong> (confidence{' '}
              {dryRun.data.confidence})
            </p>
          )}
        </div>
        {mutation.isError &&
          !mutation.error.fieldError('merchant') &&
          !mutation.error.fieldError('categoryId') && (
            <p className={styles.error} role="alert">
              {budgetsErrorCopy(mutation.error)}
            </p>
          )}
        <div className={styles.dialogActions}>
          <QlButton type="button" variant="ghost" onClick={onClose}>
            Cancel
          </QlButton>
          <QlButton type="submit" variant="primary" loading={mutation.isPending}>
            {initial ? 'Save' : 'Add rule'}
          </QlButton>
        </div>
      </form>
    </DialogShell>
  )
}

function DeleteRuleDialog({ rule, onClose }: { rule: CategoryRuleResponse; onClose: () => void }) {
  const remove = useDeleteCategoryRuleMutation()
  return (
    <DialogShell title={`Delete the rule for ${rule.merchant}?`} onClose={onClose} testId="delete-rule-dialog">
      <p className={styles.lede}>Nothing already categorised by this rule is changed.</p>
      {remove.isError && (
        <p className={styles.error} role="alert">
          {budgetsErrorCopy(remove.error)}
        </p>
      )}
      <div className={styles.dialogActions}>
        <QlButton variant="ghost" onClick={onClose}>
          Keep it
        </QlButton>
        <QlButton variant="danger" loading={remove.isPending} onClick={() => remove.mutate(rule.id, { onSuccess: onClose })}>
          Delete
        </QlButton>
      </div>
    </DialogShell>
  )
}

export default function Rules() {
  const q = useCategoryRules()
  const reorder = useReorderCategoryRulesMutation()
  const [dialog, setDialog] = useState<
    { kind: 'create' } | { kind: 'edit'; rule: CategoryRuleResponse } | { kind: 'delete'; rule: CategoryRuleResponse } | null
  >(null)

  const rules = q.data ?? []

  function move(index: number, direction: -1 | 1) {
    const target = index + direction
    if (target < 0 || target >= rules.length) return
    const next = [...rules]
    const [moved] = next.splice(index, 1)
    next.splice(target, 0, moved)
    reorder.mutate({ orderedIds: next.map((r) => r.id) })
  }

  return (
    <div className={styles.page}>
      <div className={styles.header}>
        <div>
          <h1 className={styles.heading}>Budgets</h1>
          <p className={styles.lede}>
            Rules are checked in the order below — the first one that matches a merchant wins.
            Move a rule up to give it precedence over the ones after it.
          </p>
        </div>
        <div className={styles.headerActions}>
          <QlButton variant="primary" onClick={() => setDialog({ kind: 'create' })}>
            Add rule
          </QlButton>
        </div>
      </div>
      <BudgetsTabs />

      <QlCard>
        <DryRunTester />
      </QlCard>

      <QlCard>
        {q.isPending && <QlSkeleton data-testid="rules-skeleton" />}
        {q.isError && (
          <p className={styles.error} role="alert">
            {budgetsErrorCopy(q.error)}
          </p>
        )}
        {reorder.isError && (
          <p className={styles.error} role="alert">
            {budgetsErrorCopy(reorder.error)}
          </p>
        )}
        {q.data && rules.length === 0 && <p className={styles.hint}>No merchant rules yet.</p>}
        {q.data && rules.length > 0 && (
          <ol className={styles.ruleList} data-testid="rule-list" aria-label="Merchant rules, in priority order">
            {rules.map((rule, index) => (
              <li key={rule.id} className={styles.ruleRow} data-testid={`rule-row-${index}`}>
                <div className={styles.ruleMain}>
                  <p className={styles.ruleMerchant}>{rule.merchant}</p>
                  <p className={styles.ruleMeta}>
                    <QlBadge variant="neutral">{rule.matchType === 'EXACT' ? 'Exact' : 'Starts with'}</QlBadge>
                    <span>→ {rule.categoryName ?? 'Uncategorised'}</span>
                    <span>· confidence {rule.confidence}</span>
                  </p>
                </div>
                <div className={styles.ruleActions}>
                  <div className={styles.reorderGroup} role="group" aria-label={`Reorder ${rule.merchant}`}>
                    <button
                      type="button"
                      className={styles.reorderBtn}
                      disabled={index === 0 || reorder.isPending}
                      onClick={() => move(index, -1)}
                      aria-label={`Move ${rule.merchant} up, higher priority`}
                    >
                      ▲
                    </button>
                    <button
                      type="button"
                      className={styles.reorderBtn}
                      disabled={index === rules.length - 1 || reorder.isPending}
                      onClick={() => move(index, 1)}
                      aria-label={`Move ${rule.merchant} down, lower priority`}
                    >
                      ▼
                    </button>
                  </div>
                  <QlButton size="sm" variant="ghost" onClick={() => setDialog({ kind: 'edit', rule })}>
                    Edit
                  </QlButton>
                  <QlButton size="sm" variant="ghost" onClick={() => setDialog({ kind: 'delete', rule })}>
                    Delete
                  </QlButton>
                </div>
              </li>
            ))}
          </ol>
        )}
      </QlCard>

      {dialog?.kind === 'create' && <RuleFormDialog onClose={() => setDialog(null)} />}
      {dialog?.kind === 'edit' && <RuleFormDialog initial={dialog.rule} onClose={() => setDialog(null)} />}
      {dialog?.kind === 'delete' && <DeleteRuleDialog rule={dialog.rule} onClose={() => setDialog(null)} />}
    </div>
  )
}
