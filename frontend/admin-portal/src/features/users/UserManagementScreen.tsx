import { useState } from 'react';
import { Alert, Box, Button, Snackbar, Stack, Tab, Tabs, TextField } from '@mui/material';
import PersonAddAltRoundedIcon from '@mui/icons-material/PersonAddAltRounded';
import { ModuleScreen } from '@components/ModuleScreen';
import { QueryStateView } from '@components/QueryStateView';
import { DataTable, type Column } from '@components/DataTable';
import { StatusChip } from '@components/StatusChip';
import { formatDate } from '@lib/format';
import { useUpdateUserStatus, useUsers } from './hooks';
import type { AdminUser } from './api';
import { InvitationsPanel } from './InvitationsPanel';
import { InviteStaffDialog } from './InviteStaffDialog';

/**
 * User Management module (Requirement 19.2). Lists platform users with search,
 * shows their roles and account status, and lets an admin suspend or reactivate
 * an account. Every action is recorded server-side in the Audit_Log (Req 19.8).
 *
 * The Invitations tab lists open staff invitations; "Invite staff" emails a new
 * one (email-auth Requirement 6). An email sign-up that never entered its code
 * shows as Unverified and offers no action: it cannot sign in until verified,
 * and the Auth Service removes it after 24 hours.
 */
export function UserManagementScreen() {
  const [tab, setTab] = useState<'users' | 'invitations'>('users');
  const [inviting, setInviting] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [searchInput, setSearchInput] = useState('');
  const [search, setSearch] = useState('');
  const usersQuery = useUsers(search);
  const updateStatus = useUpdateUserStatus();

  const columns: Column<AdminUser>[] = [
    { key: 'name', header: 'Name', render: (row) => row.displayName ?? '—' },
    { key: 'mobile', header: 'Mobile', render: (row) => row.mobileNumber },
    { key: 'email', header: 'Email', render: (row) => row.email ?? '—' },
    { key: 'roles', header: 'Roles', render: (row) => row.roles.join(', ') },
    {
      key: 'status',
      header: 'Status',
      render: (row) =>
        row.status === 'PENDING_VERIFICATION' ? (
          <StatusChip status={row.status} label="Unverified" />
        ) : (
          <StatusChip status={row.status} />
        ),
    },
    { key: 'joined', header: 'Joined', render: (row) => formatDate(row.createdAt) },
    {
      key: 'action',
      header: '',
      align: 'right',
      render: (row) => {
        if (row.status === 'PENDING_VERIFICATION') return null;
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
      title="User Management"
      description="Search, review, and manage user accounts."
      actions={
        <Button
          variant="contained"
          startIcon={<PersonAddAltRoundedIcon />}
          onClick={() => setInviting(true)}
        >
          Invite staff
        </Button>
      }
    >
      <Box sx={{ borderBottom: 1, borderColor: 'divider', mb: 2 }}>
        <Tabs value={tab} onChange={(_, next: 'users' | 'invitations') => setTab(next)}>
          <Tab value="users" label="Users" />
          <Tab value="invitations" label="Invitations" />
        </Tabs>
      </Box>

      {tab === 'invitations' ? <InvitationsPanel onNotice={setNotice} /> : null}

      <Box hidden={tab !== 'users'}>
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

        {/* Surfaces the service's refusals verbatim, e.g. changing your own
          account or a Super Admin's without being one. */}
        {updateStatus.isError ? (
          <Alert severity="error" sx={{ mb: 2 }}>
            {updateStatus.error.message}
          </Alert>
        ) : null}

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
      </Box>

      {inviting ? (
        <InviteStaffDialog
          onClose={() => setInviting(false)}
          onInvited={(email) => {
            setInviting(false);
            setTab('invitations');
            setNotice(`Invitation sent to ${email}`);
          }}
        />
      ) : null}

      <Snackbar
        open={notice !== null}
        autoHideDuration={4000}
        onClose={() => setNotice(null)}
        message={notice}
      />
    </ModuleScreen>
  );
}
