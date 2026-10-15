import styles from './QlSkeleton.module.css'

type Variant = 'card' | 'text' | 'row'

interface Props {
  variant?: Variant
  rows?: number
  'data-testid'?: string
}

export function QlSkeleton({ variant = 'card', rows = 1, 'data-testid': testId }: Props) {
  if (variant === 'row') {
    return (
      <div className={`${styles.skeleton} ${styles.rowGroup}`} data-testid={testId}>
        {Array.from({ length: rows }).map((_, i) => (
          <div key={i} className={styles.row} data-row />
        ))}
      </div>
    )
  }
  return (
    <div
      className={`${styles.skeleton} ${variant === 'card' ? styles.card : styles.text}`}
      data-testid={testId}
    />
  )
}
