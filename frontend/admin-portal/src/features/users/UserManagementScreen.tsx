import { useState } from 'react';
import { Box, Button, Stack, TextField } from '@mui/material';
import { ModuleScreen } from '@components/ModuleScreen';
import { QueryStateView } from '@components/QueryStateView';
import { DataTable, type Column } from '@components/DataTable';
import { StatusChip } from '@components/StatusChip';
import { formatDate } from '@lib/format';
import { useUpdateUserStatus, useUsers } from './hooks';
import type { AdminUser } from './api';

/**
 * User Management module (Requirement 19.2). Lists platform users with search,
 * shows their roles and account status, and lets an admin suspend or reactivate
 * an account. Every action is recorded server-side in the Audit_Log (Req 19.8).
 */
export function UserManagementScreen() {
  const [searchInput, setSearchInput] = useState('');
  const [search, setSearch] = useState('');
  const usersQuery = useUsers(search);
  const updateStatus = useUpdateUserStatus();

  const columns: Column<AdminUser>[] = [
    { key: 'name', header: 'Name', render: (row) => row.displayName },
    { key: 'mobile', header: 'Mobile', render: (row) => row.mobileNumber },
    { key: 'email', header: 'Email', render: (row) => row.email ?? '—' },
    { key: 'roles', header: 'Roles', render: (row) => row.roles.join(', ') },
    { key: 'status', header: 'Status', render: (row) => <StatusChip status={row.status} /> },
    { key: 'joined', header: 'Joined', render: (row) => formatDate(row.createdAt) },
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
    <ModuleScreen title="User Management" description="Search, review, and manage user accounts.">
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
          label="Search by name, mobile, or email"
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
        isLoading={usersQuery.isLoading}
        isError={usersQuery.isError}
        error={usersQuery.error}
        onRetry={() => void usersQuery.refetch()}
        isEmpty={(usersQuery.data?.length ?? 0) === 0}
        emptyMessage="No users match your search."
      >
        <DataTable columns={columns} rows={usersQuery.data ?? []} rowKey={(row) => row.id} />
      </QueryStateView>
    </ModuleScreen>
  );
}
