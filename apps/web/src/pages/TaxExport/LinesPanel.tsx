/**
 * Tax lines — the per-category, per-member override (§4.12, step 203).
 *
 * A category with no row at all still appears here (empty `lineCode`/
 * `lineLabel`, `deductible: false`) rather than being dropped — a transaction
 * that vanished from the export because nobody wrote a label for its category
 * would be a silent loss. `overridden` says whether this is the member's own
 * row or the install-wide seed.
 *
 * There is no empty-string clear (`lineCode` is `minLength: 1` on the wire).
 * "Mark as no line" is the closest the contract offers: it PUTs the seeded
 * `lineCode: 'NONE'` convention rather than deleting anything — there is no
 * delete route for a tax-line mapping.
 */
import { useState } from 'react'
import { QlButton } from '@components/ui/QlButton/QlButton'
import { QlCard } from '@components/ui/QlCard/QlCard'
import { QlInput } from '@components/ui/QlInput/QlInput'
import { QlSkeleton } from '@components/ui/QlSkeleton/QlSkeleton'
import { EmptyState } from '@components/EmptyState/EmptyState'
import { useSetTaxLineMutation, useTaxLines } from '@hooks/useTaxExport'
import { taxExportErrorCopy } from './errorCopy'
import type { TaxLineMappingResponse } from '@api/types'
import styles from './TaxExport.module.css'

function LineRow({ line }: { line: TaxLineMappingResponse }) {
  const save = useSetTaxLineMutation()
  const [lineCode, setLineCode] = useState(line.lineCode)
  const [lineLabel, setLineLabel] = useState(line.lineLabel)
  const [deductible, setDeductible] = useState(line.deductible)
  const [saved, setSaved] = useState(false)

  function submit(e: React.FormEvent) {
    e.preventDefault()
    setSaved(false)
    save.mutate(
      {
        categoryId: line.categoryId,
        body: { jurisdiction: line.jurisdiction, lineCode, lineLabel, deductible },
      },
      { onSuccess: () => setSaved(true) }
    )
  }

  function markNoLine() {
    setSaved(false)
    save.mutate(
      {
        categoryId: line.categoryId,
        body: {
          jurisdiction: line.jurisdiction,
          lineCode: 'NONE',
          lineLabel: 'No line on this form',
          deductible: false,
        },
      },
      {
        onSuccess: (next) => {
          setLineCode(next.lineCode)
          setLineLabel(next.lineLabel)
          setDeductible(next.deductible)
          setSaved(true)
        },
      }
    )
  }

  return (
    <li className={styles.lineRow} data-testid={`tax-line-${line.categorySlug}`}>
      <form className={styles.lineMain} onSubmit={submit}>
        <p className={styles.lineName}>
          {line.categoryName}
          {line.overridden && <span className={styles.lineMeta}> · your own override</span>}
        </p>
        <QlInput
          label="Line code"
          value={lineCode}
          onChange={(e) => setLineCode(e.target.value)}
          error={save.error?.fieldError('lineCode')}
        />
        <QlInput
          label="Line wording"
          value={lineLabel}
          onChange={(e) => setLineLabel(e.target.value)}
          error={save.error?.fieldError('lineLabel')}
        />
        <label className={styles.lineMeta}>
          <input
            type="checkbox"
            checked={deductible}
            onChange={(e) => setDeductible(e.target.checked)}
          />{' '}
          Deductible
        </label>
        {save.isError && (
          <p className={styles.error} role="alert">
            {taxExportErrorCopy(save.error)}
          </p>
        )}
        {saved && (
          <p className={styles.status} role="status">
            Saved.
          </p>
        )}
        <div className={styles.rowActions}>
          <QlButton type="button" variant="ghost" size="sm" onClick={markNoLine} loading={save.isPending}>
            Mark as no line
          </QlButton>
          <QlButton type="submit" variant="primary" size="sm" loading={save.isPending}>
            Save
          </QlButton>
        </div>
      </form>
    </li>
  )
}

export function LinesPanel() {
  const lines = useTaxLines()
  const rows = lines.data ?? []

  return (
    <QlCard>
      <h2 className={styles.sectionTitle}>Tax lines</h2>
      <p className={styles.hint}>
        Which line on your own form each category exports under. These are labels you write, not a
        calculation — Trove never fills one in for you.
      </p>
      {lines.isPending && <QlSkeleton data-testid="tax-lines-skeleton" />}
      {lines.isError && (
        <p className={styles.error} role="alert">
          {taxExportErrorCopy(lines.error)}
        </p>
      )}
      {lines.data && rows.length === 0 && (
        <EmptyState title="No categories yet">
          <p>Categories will appear here once your household has some.</p>
        </EmptyState>
      )}
      {lines.data && rows.length > 0 && (
        <ul className={styles.list} data-testid="tax-lines-list">
          {rows.map((line) => (
            <LineRow key={line.categoryId} line={line} />
          ))}
        </ul>
      )}
    </QlCard>
  )
}
