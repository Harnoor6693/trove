import styles from './QlButton.module.css';

type Variant = 'primary' | 'accent' | 'ghost' | 'danger' | 'dark';
type Size = 'sm' | 'md' | 'lg';

interface QlButtonProps extends React.ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: Variant;
  size?: Size;
  loading?: boolean;
}

export function QlButton({
  variant = 'ghost',
  size = 'md',
  loading = false,
  children,
  disabled,
  ...props
}: QlButtonProps) {
  return (
    <button
      data-testid={`ql-button-${variant}`}
      className={`${styles.btn} ${styles[variant]} ${styles[size]} ${loading ? styles.loading : ''}`}
      disabled={disabled || loading}
      aria-busy={loading}
      {...props}
    >
      {loading && <span className={styles.spinner} aria-hidden="true" />}
      <span className={styles.label}>{children}</span>
    </button>
  );
}
