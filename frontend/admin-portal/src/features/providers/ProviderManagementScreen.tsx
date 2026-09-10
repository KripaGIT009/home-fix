import { useState } from 'react';
import { Box, Button, Rating, Stack, TextField, Typography } from '@mui/material';
import { ModuleScreen } from '@components/ModuleScreen';
import { QueryStateView } from '@components/QueryStateView';
import { DataTable, type Column } from '@components/DataTable';
import { StatusChip } from '@components/StatusChip';
import { formatNumber } from '@lib/format';
import { useProviders, useUpdateProviderStatus } from './hooks';
import type { AdminProvider } from './api';

/**
 * Provider Management module (Requirement 19.2). Lists service providers with
 * search, shows their verification status, rating and completed-job counts, and
 * lets an admin suspend or reactivate a provider account. Every action is
 * recorded server-side in the Audit_Log (Req 19.8).
 */
export function ProviderManagementScreen() {
  const [searchInput, setSearchInput] = useState('');
  const [search, setSearch] = useState('');
  const providersQuery = useProviders(search);
  const updateStatus = useUpdateProviderStatus();

  const columns: Column<AdminProvider>[] = [
    { key: 'name', header: 'Provider', render: (row) => row.displayName },
    { key: 'mobile', header: 'Mobile', render: (row) => row.mobileNumber },
    { key: 'skill', header: 'Primary skill', render: (row) => row.primarySkill },
    {
      key: 'verification',
      header: 'Verification',
      render: (row) => <StatusChip status={row.verificationStatus} />,
    },
    {
      key: 'rating',
      header: 'Rating',
      render: (row) => (
        <Stack direction="row" spacing={0.5} alignItems="center">
          <Rating value={row.rating} precision={0.1} size="small" readOnly />
          <Typography variant="caption" color="text.secondary">
            {row.rating.toFixed(1)}
          </Typography>
        </Stack>
      ),
    },
    {
      key: 'jobs',
      header: 'Jobs',
      align: 'right',
      render: (row) => formatNumber(row.completedJobs),
    },
    {
      key: 'online',
      header: 'Availability',
      render: (row) => <StatusChip status={row.isOnline ? 'ONLINE' : 'INACTIVE'} />,
    },
    { key: 'status', header: 'Status', render: (row) => <StatusChip status={row.status} /> },
    {
      key: 'action',
      header: '',
      align: 'right',
      render: (row) => {
        const suspend = row.status === 'ACTIVE';
        return (
          <Button
            size="small"
            color={suspend ? 'error' : 'success'}
            variant="outlined"
            disabled={updateStatus.isPending}
            onClick={() =>
              updateStatus.mutate({ id: row.id, status: suspend ? 'SUSPENDED' : 'ACTIVE' })
            }
          >
            {suspend ? 'Suspend' : 'Reactivate'}
          </Button>
        );
      },
    },
  ];

  return (
    <ModuleScreen
      title="Provider Management"
      description="Search, review, and manage service provider accounts."
    >
      <Stack
        component="form"
        direction="row"
        spacing={1}
        sx={{ mb: 2 }}
        onSubmit={(event) => {
          event.preventDefault();
          setSearch(searchInput.trim());
        }}
      >
        <TextField
          size="small"
          label="Search by name, mobile, or skill"
          value={searchInput}
          onChange={(event) => setSearchInput(event.target.value)}
          sx={{ maxWidth: 360, flexGrow: 1 }}
        />
        <Box>
          <Button type="submit" variant="contained">
            Search
          </Button>
        </Box>
      </Stack>

      <QueryStateView
        isLoading={providersQuery.isLoading}
        isError={providersQuery.isError}
        error={providersQuery.error}
        onRetry={() => void providersQuery.refetch()}
        isEmpty={(providersQuery.data?.length ?? 0) === 0}
        emptyMessage="No providers match your search."
      >
        <DataTable columns={columns} rows={providersQuery.data ?? []} rowKey={(row) => row.id} />
      </QueryStateView>
    </ModuleScreen>
  );
}
