import styles from './QlBadge.module.css';

type BadgeVariant = 'sage' | 'red' | 'amber' | 'blue' | 'neutral';

interface QlBadgeProps {
  variant?: BadgeVariant;
  children: React.ReactNode;
  dot?: boolean;
}

export function QlBadge({ variant = 'neutral', children, dot = false }: QlBadgeProps) {
  return (
    <span data-testid={`ql-badge-${variant}`} className={`${styles.badge} ${styles[variant]}`}>
      {dot && <span className={styles.dot} aria-hidden="true" />}
      {children}
    </span>
  );
}
