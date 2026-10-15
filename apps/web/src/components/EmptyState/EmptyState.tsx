import styles from './EmptyState.module.css'

export interface EmptyStateProps {
  title: string
  children?: React.ReactNode
  actions?: React.ReactNode
  variant?: 'default' | 'pending'
  badge?: string
  'data-testid'?: string
}

/** A neutral, number-free placeholder. It never renders a value. */
export function EmptyState({
  title,
  children,
  actions,
  variant = 'default',
  badge,
  'data-testid': testId,
}: EmptyStateProps) {
  return (
    <div
      className={`${styles.empty} ${variant === 'pending' ? styles.pending : ''}`}
      data-testid={testId}
    >
      {badge && <span className={styles.badge}>{badge}</span>}
      <h2 className={styles.title}>{title}</h2>
      {children && <div className={styles.body}>{children}</div>}
      {actions && <div className={styles.actions}>{actions}</div>}
    </div>
  )
}
