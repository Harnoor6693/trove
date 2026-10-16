import styles from './QlProgress.module.css';

interface QlProgressProps {
  value: number;
  max?: number;
  label?: string;
  showLabel?: boolean;
  height?: number;
  over?: boolean;
}

export function QlProgress({ value, max = 100, label, showLabel = false, height = 3, over = false }: QlProgressProps) {
  const pct = Math.min(100, (value / max) * 100);
  const isOver = over || value > max;
  return (
    <div
      data-testid={isOver ? 'ql-progress-over' : 'ql-progress'}
      className={styles.wrapper}
      role="progressbar"
      aria-valuenow={value}
      aria-valuemin={0}
      aria-valuemax={max}
      aria-label={label}
    >
      {showLabel && label && (
        <div className={styles.labelRow}>
          <span className={styles.label}>{label}</span>
          <span className={styles.pct}>{Math.round(pct)}%</span>
        </div>
      )}
      <div className={styles.track} style={{ height }}>
        <div
          data-testid="ql-progress-fill"
          className={`${styles.fill} ${isOver ? styles.over : ''}`}
          style={{ width: `${pct}%` }}
        />
      </div>
    </div>
  );
}
