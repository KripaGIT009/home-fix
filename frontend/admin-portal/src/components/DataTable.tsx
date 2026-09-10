import type { ReactNode } from 'react';
import {
  Paper,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Typography,
} from '@mui/material';
import { brand } from '@lib/theme';

export interface Column<Row> {
  /** Stable key; also used as the React key for the header cell. */
  key: string;
  /** Column header label. */
  header: string;
  /** Cell renderer for a given row. */
  render: (row: Row) => ReactNode;
  /** Optional column alignment. */
  align?: 'left' | 'right' | 'center';
  /** Optional fixed/most-min width. */
  width?: number | string;
}

interface DataTableProps<Row> {
  columns: readonly Column<Row>[];
  rows: readonly Row[];
  /** Extract a stable key for each row. */
  rowKey: (row: Row) => string;
  /** Optional row click handler (e.g. drill-in). */
  onRowClick?: (row: Row) => void;
}

/**
 * Thin, typed wrapper around MUI Table for the read-heavy admin modules. Keeps
 * the module screens declarative: define columns + rows, and get consistent
 * styling, alignment, and optional row-click behaviour.
 *
 * Headers are a quiet tinted band and rows sit on hairline dividers, so long
 * operational tables stay scannable; the table scrolls horizontally inside its
 * own container rather than forcing the page sideways on narrow screens.
 */
export function DataTable<Row>({ columns, rows, rowKey, onRowClick }: DataTableProps<Row>) {
  return (
    <TableContainer
      component={Paper}
      sx={{
        border: 1,
        borderColor: 'divider',
        overflowX: 'auto',
        '& .MuiTableCell-root': { borderColor: 'divider' },
      }}
    >
      <Table size="small">
        <TableHead>
          <TableRow sx={{ '& .MuiTableCell-root': { bgcolor: brand.canvas, py: 1.25 } }}>
            {columns.map((col) => (
              <TableCell
                key={col.key}
                align={col.align ?? 'left'}
                sx={{
                  fontWeight: 700,
                  color: 'text.secondary',
                  whiteSpace: 'nowrap',
                  ...(col.width !== undefined ? { width: col.width } : {}),
                }}
              >
                {col.header}
              </TableCell>
            ))}
          </TableRow>
        </TableHead>
        <TableBody>
          {rows.map((row) => (
            <TableRow
              key={rowKey(row)}
              hover={Boolean(onRowClick)}
              {...(onRowClick ? { onClick: () => onRowClick(row), sx: { cursor: 'pointer' } } : {})}
            >
              {columns.map((col) => (
                <TableCell key={col.key} align={col.align ?? 'left'} sx={{ py: 1.25 }}>
                  {col.render(row)}
                </TableCell>
              ))}
            </TableRow>
          ))}
          {rows.length === 0 ? (
            <TableRow>
              <TableCell colSpan={columns.length} align="center" sx={{ py: 5, border: 0 }}>
                <Typography variant="body2" color="text.secondary">
                  No records match the current filters.
                </Typography>
              </TableCell>
            </TableRow>
          ) : null}
        </TableBody>
      </Table>
    </TableContainer>
  );
}
