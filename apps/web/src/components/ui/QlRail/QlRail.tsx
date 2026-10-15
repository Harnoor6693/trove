import { NavLink } from 'react-router-dom';
import styles from './QlRail.module.css';
import type { NavItem } from '../QlSidebar/QlSidebar';

interface QlRailProps { items: NavItem[]; }

export function QlRail({ items }: QlRailProps) {
  return (
    <nav data-testid="ql-rail" className={styles.rail} aria-label="Main navigation">
      <div className={styles.logo} aria-hidden="true">t</div>
      {items.map((item) => (
        <NavLink
          key={item.to}
          to={item.to}
          end={item.to === '/'}
          data-testid="ql-rail-item"
          title={item.label}
          className={({ isActive }) => `${styles.item} ${isActive ? `${styles.active} active` : ''}`}
          aria-label={item.label}
        >
          <span className={styles.icon} aria-hidden="true">{item.icon}</span>
          {item.badge != null && (
            <span className={styles.badge} aria-hidden="true">{item.badge}</span>
          )}
        </NavLink>
      ))}
    </nav>
  );
}
