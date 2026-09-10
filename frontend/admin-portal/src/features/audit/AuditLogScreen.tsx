import { useState } from 'react';
import { Box, Button, Chip, MenuItem, Stack, TextField } from '@mui/material';
import { ModuleScreen } from '@components/ModuleScreen';
import { QueryStateView } from '@components/QueryStateView';
import { DataTable, type Column } from '@components/DataTable';
import { formatDateTime } from '@lib/format';
import { useAuditLogs } from './hooks';
import type { AuditAction, AuditLogEntry } from './api';

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
 * entity, change summary, and timestamp. Supports cursor-based pagination.
 */
export function AuditLogScreen() {
  const [actionInput, setActionInput] = useState<AuditAction | ''>('');
  const [entityInput, setEntityInput] = useState('');
  const [filters, setFilters] = useState<{ action: AuditAction | ''; entityType: string }>({
    action: '',
    entityType: '',
  });
  const [cursor, setCursor] = useState<string | undefined>(undefined);

  const logsQuery = useAuditLogs({
    action: filters.action,
    ...(filters.entityType ? { entityType: filters.entityType } : {}),
    ...(cursor ? { cursor } : {}),
  });

  const applyFilters = () => {
    setCursor(undefined);
    setFilters({ action: actionInput, entityType: entityInput.trim() });
  };

  const columns: Column<AuditLogEntry>[] = [
    { key: 'time', header: 'Timestamp', render: (row) => formatDateTime(row.timestamp) },
    { key: 'actor', header: 'Actor', render: (row) => row.actorName },
    {
      key: 'action',
      header: 'Action',
      render: (row) => <Chip size="small" color={ACTION_COLOR[row.action]} label={row.action} />,
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

      <QueryStateView
        isLoading={logsQuery.isLoading}
        isError={logsQuery.isError}
        error={logsQuery.error}
        onRetry={() => void logsQuery.refetch()}
        isEmpty={(logsQuery.data?.entries.length ?? 0) === 0}
        emptyMessage="No audit log entries match your filters."
      >
        <DataTable
          columns={columns}
          rows={logsQuery.data?.entries ?? []}
          rowKey={(row) => row.id}
        />
        {logsQuery.data?.nextCursor ? (
          <Box sx={{ mt: 2, display: 'flex', justifyContent: 'center' }}>
            <Button variant="outlined" onClick={() => setCursor(logsQuery.data?.nextCursor)}>
              Load more
            </Button>
          </Box>
        ) : null}
      </QueryStateView>
    </ModuleScreen>
  );
}
