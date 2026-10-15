import { useEffect, useCallback } from 'react';
import { createPortal } from 'react-dom';
import styles from './QlModal.module.css';

interface QlModalProps {
  isOpen: boolean;
  onClose: () => void;
  title?: string;
  children: React.ReactNode;
  size?: 'sm' | 'md' | 'lg';
}

export function QlModal({ isOpen, onClose, title, children, size = 'md' }: QlModalProps) {
  const handleKeyDown = useCallback((e: KeyboardEvent) => {
    if (e.key === 'Escape') onClose();
  }, [onClose]);

  useEffect(() => {
    if (isOpen) {
      document.addEventListener('keydown', handleKeyDown);
      document.body.style.overflow = 'hidden';
    }
    return () => {
      document.removeEventListener('keydown', handleKeyDown);
      document.body.style.overflow = '';
    };
  }, [isOpen, handleKeyDown]);

  if (!isOpen) {
    return <div data-testid="ql-modal" style={{ display: 'none' }} aria-hidden="true" />;
  }

  return createPortal(
    <div
      data-testid="ql-modal-backdrop"
      className={styles.backdrop}
      onClick={(e) => { if (e.target === e.currentTarget) onClose(); }}
    >
      <div
        data-testid="ql-modal"
        role="dialog"
        aria-modal="true"
        aria-labelledby={title ? 'ql-modal-title' : undefined}
        className={`${styles.modal} ${styles[size]}`}
      >
        <div className={styles.header}>
          {title && <h2 id="ql-modal-title" className={styles.title}>{title}</h2>}
          <button className={styles.close} onClick={onClose} aria-label="Close modal">×</button>
        </div>
        <div className={styles.body}>{children}</div>
      </div>
    </div>,
    document.body
  );
}
