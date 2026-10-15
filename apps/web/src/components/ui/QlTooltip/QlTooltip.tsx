import { useState } from 'react';
import styles from './QlTooltip.module.css';

type TooltipPlacement = 'top' | 'bottom' | 'left' | 'right';

interface QlTooltipProps {
  content: string;
  placement?: TooltipPlacement;
  children: React.ReactNode;
}

export function QlTooltip({ content, placement = 'top', children }: QlTooltipProps) {
  const [visible, setVisible] = useState(false);
  return (
    <span
      className={styles.wrapper}
      onMouseEnter={() => setVisible(true)}
      onMouseLeave={() => setVisible(false)}
      onFocus={() => setVisible(true)}
      onBlur={() => setVisible(false)}
    >
      <span data-testid="ql-tooltip-trigger" className={styles.trigger}>{children}</span>
      <span
        data-testid="ql-tooltip-content"
        role="tooltip"
        className={`${styles.tooltip} ${styles[placement]} ${visible ? styles.visible : ''}`}
      >
        {content}
      </span>
    </span>
  );
}
