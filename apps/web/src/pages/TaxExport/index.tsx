/**
 * Tax export — an EXPORT, not an engine (§4.12, step 203, the hardest rule in
 * this family). It emits the member's own amounts and labels and NO computed
 * figure of any kind — no rate, no tax owed, no total the server did not
 * send. Nothing on this screen may render a summary line, a percentage, an
 * estimate, or let a chart imply one — there is no chart here at all.
 */
import { LinesPanel } from './LinesPanel'
import { RunsPanel } from './RunsPanel'
import styles from './TaxExport.module.css'

export default function TaxExport() {
  return (
    <div className={styles.page}>
      <div className={styles.header}>
        <div>
          <h1 className={styles.heading}>Tax export</h1>
          <p className={styles.lede}>
            Trove exports your own categorised transactions and labels for your accountant. It
            does not compute tax, estimate a refund or model RRSP room — nothing here is a
            calculation.
          </p>
        </div>
      </div>

      <LinesPanel />
      <RunsPanel />
    </div>
  )
}
