/**
 * Categories — the household's category tree, and every action on one.
 *
 * `CategoryResponse` carries a `parentId` and an `isSystem` flag: this is a
 * TREE, not a flat list, and a system category (seeded, `householdId: null`)
 * cannot be renamed, re-parented or deleted through this API at all (403
 * `forbidden`) — so this screen never offers those actions on one in the first
 * place, rather than rendering a button that always fails (§202 scope item 3).
 *
 * Deleting a category still referenced by one of this household's own
 * merchant rules is 409 `conflict`; the dialog shows `error.detail`, which is
 * the server's own words for what it is used by.
 *
 * Accessibility: each parent with children is a real disclosure button
 * (`aria-expanded` + `aria-controls`), the WAI-ARIA disclosure pattern rather
 * than the full ARIA treeview role — every toggle is a native `<button>`,
 * reachable and operable from the keyboard with no custom key handling to get
 * wrong, and a screen reader announces the expanded/collapsed state on the
 * button itself.
 */
import { useId, useMemo, useState } from 'react'
import { QlButton } from '@components/ui/QlButton/QlButton'
import { QlCard } from '@components/ui/QlCard/QlCard'
import { QlBadge } from '@components/ui/QlBadge/QlBadge'
import { QlInput } from '@components/ui/QlInput/QlInput'
import { QlModal } from '@components/ui/QlModal/QlModal'
import { QlSkeleton } from '@components/ui/QlSkeleton/QlSkeleton'
import { useDialogFocus } from '@hooks/useDialogFocus'
import {
  useCategories,
  useCreateCategoryMutation,
  useDeleteCategoryMutation,
  useUpdateCategoryMutation,
} from '@hooks/useCategories'
import { budgetsErrorCopy } from './errorCopy'
import { BudgetsTabs } from './BudgetsTabs'
import type { CategoryResponse } from '@api/types'
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

interface CategoryFormProps {
  categories: CategoryResponse[]
  initial?: CategoryResponse
  onClose: () => void
}

function CategoryFormDialog({ categories, initial, onClose }: CategoryFormProps) {
  const create = useCreateCategoryMutation()
  const update = useUpdateCategoryMutation()
  const [name, setName] = useState(initial?.name ?? '')
  const [parentId, setParentId] = useState(initial?.parentId ?? '')
  const mutation = initial ? update : create

  // A category cannot be its own parent, and — one level deep, which is all this
  // fixture-free rule can check client-side without walking the whole tree —
  // cannot be parented under its own direct child either. The server is the
  // authority on a deeper cycle; this is a hint, not a gate.
  const parentOptions = categories.filter(
    (c) => c.id !== initial?.id && c.parentId !== initial?.id
  )

  function submit(e: React.FormEvent) {
    e.preventDefault()
    const body = { name, parentId: parentId || null, icon: initial?.icon ?? null }
    if (initial) {
      update.mutate({ id: initial.id, body }, { onSuccess: onClose })
    } else {
      create.mutate(body, { onSuccess: onClose })
    }
  }

  return (
    <DialogShell
      title={initial ? `Rename ${initial.name}` : 'Add a category'}
      onClose={onClose}
      testId="category-form-dialog"
    >
      <form className={styles.stack} onSubmit={submit}>
        <QlInput
          label="Name"
          value={name}
          onChange={(e) => setName(e.target.value)}
          error={mutation.error?.fieldError('name')}
          required
        />
        <label className={styles.selectLabel} htmlFor="category-parent">
          Parent category
        </label>
        <select
          id="category-parent"
          className={styles.select}
          value={parentId}
          onChange={(e) => setParentId(e.target.value)}
        >
          <option value="">No parent — top level</option>
          {parentOptions.map((c) => (
            <option key={c.id} value={c.id}>
              {c.name}
              {c.isSystem ? ' (system)' : ''}
            </option>
          ))}
        </select>
        {mutation.isError && !mutation.error.fieldError('name') && (
          <p className={styles.error} role="alert">
            {budgetsErrorCopy(mutation.error)}
          </p>
        )}
        <div className={styles.dialogActions}>
          <QlButton type="button" variant="ghost" onClick={onClose}>
            Cancel
          </QlButton>
          <QlButton type="submit" variant="primary" loading={mutation.isPending}>
            {initial ? 'Save' : 'Add category'}
          </QlButton>
        </div>
      </form>
    </DialogShell>
  )
}

function DeleteCategoryDialog({
  category,
  onClose,
}: {
  category: CategoryResponse
  onClose: () => void
}) {
  const remove = useDeleteCategoryMutation()
  return (
    <DialogShell
      title={`Delete ${category.name}?`}
      onClose={onClose}
      testId="delete-category-dialog"
    >
      <p className={styles.lede}>
        Transactions already filed under this category keep their old category id; nothing is
        recategorised by this.
      </p>
      {remove.isError && (
        <p className={styles.error} role="alert" data-testid="delete-category-error">
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
          onClick={() => remove.mutate(category.id, { onSuccess: onClose })}
        >
          Delete
        </QlButton>
      </div>
    </DialogShell>
  )
}

interface TreeNode extends CategoryResponse {
  children: TreeNode[]
}

function buildTree(categories: CategoryResponse[]): TreeNode[] {
  const byId = new Map<string, TreeNode>(categories.map((c) => [c.id, { ...c, children: [] }]))
  const roots: TreeNode[] = []
  for (const node of byId.values()) {
    if (node.parentId && byId.has(node.parentId)) {
      byId.get(node.parentId)!.children.push(node)
    } else {
      roots.push(node)
    }
  }
  return roots
}

function CategoryRow({
  node,
  depth,
  onEdit,
  onDelete,
}: {
  node: TreeNode
  depth: number
  onEdit: (c: CategoryResponse) => void
  onDelete: (c: CategoryResponse) => void
}) {
  const [expanded, setExpanded] = useState(true)
  const childrenId = useId()
  const hasChildren = node.children.length > 0

  return (
    <li>
      <div className={styles.treeRow} style={{ paddingLeft: `${depth * 1.25}rem` }}>
        <div className={styles.treeMain}>
          {hasChildren ? (
            <button
              type="button"
              className={styles.treeToggle}
              aria-expanded={expanded}
              aria-controls={childrenId}
              onClick={() => setExpanded((v) => !v)}
            >
              {expanded ? '▾' : '▸'}
              <span className={styles.srOnly}>{node.name} subcategories</span>
            </button>
          ) : (
            <span className={styles.treeToggleSpacer} aria-hidden="true" />
          )}
          <span className={styles.treeName}>{node.name}</span>
          {node.isSystem && <QlBadge variant="neutral">System</QlBadge>}
        </div>
        {/* Refuse to offer a destructive action on a system category — it would
            always answer 403, and a button that always fails is worse than its
            absence (§202 scope item 3). */}
        {!node.isSystem && (
          <div className={styles.treeActions}>
            <QlButton size="sm" variant="ghost" onClick={() => onEdit(node)}>
              Rename / re-parent
            </QlButton>
            <QlButton size="sm" variant="ghost" onClick={() => onDelete(node)}>
              Delete
            </QlButton>
          </div>
        )}
      </div>
      {hasChildren && (
        <ul id={childrenId} className={styles.treeChildren} hidden={!expanded}>
          {node.children.map((child) => (
            <CategoryRow key={child.id} node={child} depth={depth + 1} onEdit={onEdit} onDelete={onDelete} />
          ))}
        </ul>
      )}
    </li>
  )
}

export default function Categories() {
  const q = useCategories()
  const [dialog, setDialog] = useState<
    | { kind: 'create' }
    | { kind: 'edit'; category: CategoryResponse }
    | { kind: 'delete'; category: CategoryResponse }
    | null
  >(null)

  const tree = useMemo(() => buildTree(q.data ?? []), [q.data])

  return (
    <div className={styles.page}>
      <div className={styles.header}>
        <div>
          <h1 className={styles.heading}>Budgets</h1>
          <p className={styles.lede}>
            Categories are shared by the whole household. A system category cannot be renamed or
            deleted — only the ones your household added can be.
          </p>
        </div>
        <div className={styles.headerActions}>
          <QlButton variant="primary" onClick={() => setDialog({ kind: 'create' })}>
            Add category
          </QlButton>
        </div>
      </div>
      <BudgetsTabs />
      <QlCard>
        {q.isPending && <QlSkeleton data-testid="categories-skeleton" />}
        {q.isError && (
          <p className={styles.error} role="alert">
            {budgetsErrorCopy(q.error)}
          </p>
        )}
        {q.data && (
          <ul className={styles.tree} data-testid="category-tree">
            {tree.map((node) => (
              <CategoryRow
                key={node.id}
                node={node}
                depth={0}
                onEdit={(c) => setDialog({ kind: 'edit', category: c })}
                onDelete={(c) => setDialog({ kind: 'delete', category: c })}
              />
            ))}
          </ul>
        )}
      </QlCard>

      {dialog?.kind === 'create' && (
        <CategoryFormDialog categories={q.data ?? []} onClose={() => setDialog(null)} />
      )}
      {dialog?.kind === 'edit' && (
        <CategoryFormDialog
          categories={q.data ?? []}
          initial={dialog.category}
          onClose={() => setDialog(null)}
        />
      )}
      {dialog?.kind === 'delete' && (
        <DeleteCategoryDialog category={dialog.category} onClose={() => setDialog(null)} />
      )}
    </div>
  )
}
