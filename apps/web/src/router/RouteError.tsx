/**
 * The router's `errorElement` (defect I8: there was none anywhere in the tree).
 *
 * It handles the two distinct cases the router raises: a 404 from an unmatched
 * URL, and an exception thrown while loading or rendering a route. Both used to
 * surface as React Router's developer overlay.
 */
import { isRouteErrorResponse, Link, useRouteError } from 'react-router-dom'
import { ApiError } from '@api/client'
import styles from '@styles/auth.module.css'

export function RouteError() {
  const error = useRouteError()

  const is404 = isRouteErrorResponse(error) && error.status === 404
  const detail =
    error instanceof ApiError
      ? (error.detail ?? error.title ?? error.message)
      : error instanceof Error
        ? error.message
        : null

  return (
    <div className={styles.page}>
      <div className={styles.card} role="alert" data-testid="route-error">
        <div className={styles.logo}>Trove</div>
        <p className={styles.lede}>
          {is404 ? "That page doesn't exist." : 'Something went wrong loading this page.'}
        </p>
        {!is404 && detail && <p className={styles.notice}>{detail}</p>}
        <p className={styles.footnote}>
          <Link to="/">Go to Today</Link>
        </p>
      </div>
    </div>
  )
}
