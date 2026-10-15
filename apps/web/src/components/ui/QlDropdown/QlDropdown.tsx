import { useState, useEffect, useRef } from 'react';
import styles from './QlDropdown.module.css';

export interface DropdownItem { label: string; value: string; icon?: React.ReactNode; danger?: boolean; }

interface QlDropdownProps {
  trigger: React.ReactNode;
  items: DropdownItem[];
  onSelect?: (item: DropdownItem) => void;
  align?: 'left' | 'right';
}

export function QlDropdown({ trigger, items, onSelect, align = 'left' }: QlDropdownProps) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    function onClickOutside(e: MouseEvent) {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false);
    }
    function onKeyDown(e: KeyboardEvent) { if (e.key === 'Escape') setOpen(false); }
    if (open) {
      document.addEventListener('mousedown', onClickOutside);
      document.addEventListener('keydown', onKeyDown);
    }
    return () => {
      document.removeEventListener('mousedown', onClickOutside);
      document.removeEventListener('keydown', onKeyDown);
    };
  }, [open]);

  return (
    <div ref={ref} className={styles.wrapper}>
      <button
        data-testid="ql-dropdown-trigger"
        className={styles.trigger}
        onClick={() => setOpen(prev => !prev)}
        aria-haspopup="menu"
        aria-expanded={open}
      >
        {trigger}
      </button>
      <div
        data-testid="ql-dropdown-menu"
        role="menu"
        className={`${styles.menu} ${styles[align]} ${open ? styles.open : ''}`}
      >
        {items.map((item, i) => (
          <button
            key={item.value}
            data-testid={`ql-dropdown-item-${i}`}
            role="menuitem"
            className={`${styles.item} ${item.danger ? styles.danger : ''}`}
            onClick={() => { onSelect?.(item); setOpen(false); }}
          >
            {item.icon && <span className={styles.itemIcon}>{item.icon}</span>}
            {item.label}
          </button>
        ))}
      </div>
    </div>
  );
}
