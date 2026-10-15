import styles from './QlTable.module.css';

export interface QlTableColumn<T> {
  key: keyof T | string;
  header: string;
  render?: (row: T, index: number) => React.ReactNode;
  align?: 'left' | 'right' | 'center';
  mono?: boolean;
  width?: string;
}

interface QlTableProps<T extends Record<string, unknown>> {
  columns: QlTableColumn<T>[];
  rows: T[];
  onRowClick?: (row: T, index: number) => void;
  emptyMessage?: string;
}

export function QlTable<T extends Record<string, unknown>>({ columns, rows, onRowClick, emptyMessage = 'No data' }: QlTableProps<T>) {
  return (
    <div className={styles.wrapper}>
      <table data-testid="ql-table" className={styles.table}>
        <thead>
          <tr>
            {columns.map((col, i) => (
              <th
                key={String(col.key)}
                data-testid={`ql-table-th-${i}`}
                className={`${styles.th} ${col.align === 'right' ? styles.right : ''}`}
                style={col.width ? { width: col.width } : undefined}
              >
                {col.header}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.length === 0 ? (
            <tr><td colSpan={columns.length} className={styles.empty}>{emptyMessage}</td></tr>
          ) : rows.map((row, ri) => (
            <tr
              key={ri}
              data-testid={`ql-table-row-${ri}`}
              className={`${styles.row} ${onRowClick ? styles.clickable : ''}`}
              onClick={() => onRowClick?.(row, ri)}
            >
              {columns.map((col, ci) => {
                const value = row[col.key as keyof T];
                return (
                  <td
                    key={String(col.key)}
                    data-testid={col.mono ? `ql-table-amount-${ri}` : `ql-table-cell-${ri}-${ci}`}
                    className={`${styles.td} ${col.align === 'right' ? styles.right : ''} ${col.mono ? styles.mono : ''}`}
                  >
                    {col.render ? col.render(row, ri) : String(value ?? '')}
                  </td>
                );
              })}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
