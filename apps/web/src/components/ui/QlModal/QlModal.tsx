import { useEffect, useId, useRef } from 'react';
import { createPortal } from 'react-dom';
import styles from './QlModal.module.css';

interface QlModalProps {
  isOpen: boolean;
  onClose: () => void;
  title?: string;
  children: React.ReactNode;
  size?: 'sm' | 'md' | 'lg';
}

/** Open modals, oldest first. Only the last one answers Escape and Tab. */
const openModals: string[] = [];

const FOCUSABLE =
  'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';

export function QlModal({ isOpen, onClose, title, children, size = 'md' }: QlModalProps) {
  const id = useId();
  const titleId = `${id}-title`;
  const dialogRef = useRef<HTMLDivElement>(null);
  const onCloseRef = useRef(onClose);
  useEffect(() => { onCloseRef.current = onClose; });

  useEffect(() => {
    if (!isOpen) return;
    openModals.push(id);
    document.body.style.overflow = 'hidden';

    const handleKeyDown = (e: KeyboardEvent) => {
      if (openModals[openModals.length - 1] !== id) return;
      if (e.key === 'Escape') {
        onCloseRef.current();
      } else if (e.key === 'Tab') {
        const items = Array.from(dialogRef.current?.querySelectorAll<HTMLElement>(FOCUSABLE) ?? []);
        if (items.length === 0) { e.preventDefault(); return; }
        const first = items[0];
        const last = items[items.length - 1];
        const active = document.activeElement;
        const outside = !dialogRef.current?.contains(active);
        if (e.shiftKey && (active === first || outside)) { e.preventDefault(); last.focus(); }
        else if (!e.shiftKey && (active === last || outside)) { e.preventDefault(); first.focus(); }
      }
    };
    document.addEventListener('keydown', handleKeyDown);
    return () => {
      document.removeEventListener('keydown', handleKeyDown);
      openModals.splice(openModals.indexOf(id), 1);
      if (openModals.length === 0) document.body.style.overflow = '';
    };
  }, [isOpen, id]);

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
        ref={dialogRef}
        data-testid="ql-modal"
        role="dialog"
        aria-modal="true"
        aria-labelledby={title ? titleId : undefined}
        className={`${styles.modal} ${styles[size]}`}
      >
        <div className={styles.header}>
          {title && <h2 id={titleId} className={styles.title}>{title}</h2>}
          <button className={styles.close} onClick={onClose} aria-label="Close modal">×</button>
        </div>
        <div className={styles.body}>{children}</div>
      </div>
    </div>,
    document.body
  );
}
