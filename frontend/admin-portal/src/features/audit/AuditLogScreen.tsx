import { useState } from 'react';
import { Alert, Box, Button, Chip, MenuItem, Stack, TextField } from '@mui/material';
import { ModuleScreen } from '@components/ModuleScreen';
import { QueryStateView } from '@components/QueryStateView';
import { DataTable, type Column } from '@components/DataTable';
import { formatDateTime } from '@lib/format';
import { useAuditLogs } from './hooks';
import type { AuditAction, AuditLogEntry, AuditLogFilters } from './api';

const ACTION_OPTIONS: ReadonlyArray<AuditAction> = [
  'CREATE',
  'UPDATE',
  'DELETE',
  'APPROVE',
  'REJECT',
];

const ACTION_COLOR: Record<AuditAction, 'success' | 'info' | 'error' | 'warning'> = {
  CREATE: 'success',
  UPDATE: 'info',
  DELETE: 'error',
  APPROVE: 'success',
  REJECT: 'warning',
};

/**
 * Audit Logs module (Requirement 19.2, Requirement 19.8). A read-only,
 * filterable view of every recorded admin action with actor, action, affected
 * entity, change summary, and timestamp. Supports cursor-based pagination:
 * "Load more" appends the next page, and applying filters starts over from the
 * newest entry.
 */
export function AuditLogScreen() {
  const [actionInput, setActionInput] = useState<AuditAction | ''>('');
  const [entityInput, setEntityInput] = useState('');
  const [filters, setFilters] = useState<AuditLogFilters>({ action: '' });

  // The filters are the query key, so new filters begin a fresh query from the
  // first page; the cursor lives inside the query's pages, never in this state.
  const logsQuery = useAuditLogs(filters);
  const entries = logsQuery.data?.pages.flatMap((page) => page.entries) ?? [];

  const applyFilters = () => {
    const entityType = entityInput.trim();
    setFilters({ action: actionInput, ...(entityType ? { entityType } : {}) });
  };

  const columns: Column<AuditLogEntry>[] = [
    { key: 'time', header: 'Timestamp', render: (row) => formatDateTime(row.timestamp) },
    { key: 'actor', header: 'Actor', render: (row) => row.actorName ?? row.actorId },
    {
      key: 'action',
      header: 'Action',
      render: (row) => (
        <Chip size="small" color={ACTION_COLOR[row.action] ?? 'default'} label={row.action} />
      ),
    },
    { key: 'entity', header: 'Entity', render: (row) => `${row.entityType} · ${row.entityId}` },
    { key: 'summary', header: 'Change', render: (row) => row.changeSummary },
  ];

  return (
    <ModuleScreen title="Audit Logs" description="Immutable record of every administrative action.">
      <Stack
        component="form"
        direction={{ xs: 'column', sm: 'row' }}
        spacing={1}
        sx={{ mb: 2 }}
        onSubmit={(event) => {
          event.preventDefault();
          applyFilters();
        }}
      >
        <TextField
          size="small"
          select
          label="Action"
          value={actionInput}
          onChange={(event) => setActionInput(event.target.value as AuditAction | '')}
          sx={{ minWidth: 160 }}
        >
          <MenuItem value="">All actions</MenuItem>
          {ACTION_OPTIONS.map((option) => (
            <MenuItem key={option} value={option}>
              {option.toLowerCase()}
            </MenuItem>
          ))}
        </TextField>
        <TextField
          size="small"
          label="Entity type"
          placeholder="e.g. Coupon, Provider"
          value={entityInput}
          onChange={(event) => setEntityInput(event.target.value)}
          sx={{ minWidth: 220 }}
        />
        <Box>
          <Button type="submit" variant="contained">
            Filter
          </Button>
        </Box>
      </Stack>

      {/* A failed "load more" keeps the pages already shown; it is reported next
          to the button instead of replacing the table. */}
      <QueryStateView
        isLoading={logsQuery.isLoading}
        isError={logsQuery.isError && !logsQuery.isFetchNextPageError}
        error={logsQuery.error}
        onRetry={() => void logsQuery.refetch()}
        isEmpty={entries.length === 0}
        emptyMessage="No audit log entries match your filters."
      >
        <DataTable columns={columns} rows={entries} rowKey={(row) => row.id} />
        {logsQuery.isFetchNextPageError ? (
          <Alert severity="error" sx={{ mt: 2 }}>
            {logsQuery.error?.message ?? 'Could not load more entries.'}
          </Alert>
        ) : null}
        {logsQuery.hasNextPage ? (
          <Box sx={{ mt: 2, display: 'flex', justifyContent: 'center' }}>
            <Button
              variant="outlined"
              disabled={logsQuery.isFetchingNextPage}
              onClick={() => void logsQuery.fetchNextPage()}
            >
              {logsQuery.isFetchingNextPage ? 'Loading…' : 'Load more'}
            </Button>
          </Box>
        ) : null}
      </QueryStateView>
    </ModuleScreen>
  );
}
