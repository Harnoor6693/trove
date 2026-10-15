/**
 * The 404 that did not exist (defect I8).
 *
 * Without a `path: '*'` route, a mistyped URL rendered React Router's raw
 * `Error: No routes matched location "…"` developer overlay — in production, to a
 * user, on a finance app.
 */
import { Link } from 'react-router-dom'
import styles from '@styles/auth.module.css'

export default function NotFound() {
  return (
    <div className={styles.page}>
      <div className={styles.card}>
        <div className={styles.logo}>Trove</div>
        <p className={styles.lede}>That page doesn't exist.</p>
        <p className={styles.footnote}>
          <Link to="/">Go to Today</Link>
        </p>
      </div>
    </div>
  )
}
