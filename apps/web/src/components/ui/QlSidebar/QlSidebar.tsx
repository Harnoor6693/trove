import { NavLink } from 'react-router-dom';
import styles from './QlSidebar.module.css';

export interface NavItem { to: string; label: string; icon: React.ReactNode; badge?: string | number; }

interface QlSidebarProps {
  items: NavItem[];
  serverInfo?: { host: string; model: string; };
}

export function QlSidebar({ items, serverInfo }: QlSidebarProps) {
  return (
    <aside data-testid="ql-sidebar" className={styles.sidebar}>
      <div className={styles.logo}>
        <div className={styles.logoIcon} aria-hidden="true">t</div>
        <span data-testid="ql-sidebar-wordmark" className={styles.wordmark}>Trove</span>
      </div>
      <nav className={styles.nav} aria-label="Main navigation">
        {items.map((item) => (
          <NavLink
            key={item.to}
            to={item.to}
            end={item.to === '/'}
            data-testid="ql-sidebar-item"
            className={({ isActive }) => `${styles.item} ${isActive ? `${styles.active} active` : ''}`}
          >
            <span className={styles.itemIcon} aria-hidden="true">{item.icon}</span>
            <span className={styles.itemLabel}>{item.label}</span>
            {item.badge != null && <span className={styles.badge}>{item.badge}</span>}
          </NavLink>
        ))}
      </nav>
      {serverInfo && (
        <div className={styles.serverCard}>
          <div className={styles.serverHeader}>
            <span className={styles.serverDot} aria-hidden="true" />
            <span className={styles.serverLabel}>Self-hosted</span>
          </div>
          <div className={styles.serverHost}>{serverInfo.host}</div>
          <div className={styles.serverModel}>{serverInfo.model}</div>
        </div>
      )}
    </aside>
  );
}
