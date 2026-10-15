import styles from './QlCard.module.css';

type CardVariant = 'default' | 'elevated' | 'flat';

interface QlCardProps {
  variant?: CardVariant;
  padding?: 'sm' | 'md' | 'lg' | 'none';
  children: React.ReactNode;
  className?: string;
  onClick?: () => void;
}

export function QlCard({ variant = 'default', padding = 'md', children, className = '', onClick }: QlCardProps) {
  const testId = variant === 'default' ? 'ql-card' : `ql-card-${variant}`;
  const handleKeyDown = (e: React.KeyboardEvent<HTMLDivElement>) => {
    if (onClick && (e.key === 'Enter' || e.key === ' ')) {
      e.preventDefault();
      onClick();
    }
  };
  return (
    <div
      data-testid={testId}
      className={`${styles.card} ${styles[variant]} ${styles[`pad_${padding}`]} ${onClick ? styles.clickable : ''} ${className}`}
      onClick={onClick}
      onKeyDown={onClick ? handleKeyDown : undefined}
      role={onClick ? 'button' : undefined}
      tabIndex={onClick ? 0 : undefined}
    >
      {children}
    </div>
  );
}
