import { useState } from 'react';
import {
  Box,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Stack,
  Tab,
  Tabs,
  Typography,
  useMediaQuery,
  type Theme,
} from '@mui/material';
import { DataTable, type Column } from '@components/DataTable';
import { QueryStateView } from '@components/QueryStateView';
import { formatMobileNumber } from '@features/auth/phone';
import { shortId } from '@lib/format';
import { AddByMobileForm } from './AddByMobileForm';
import { ConfirmRemoveDialog } from './ConfirmRemoveDialog';
import {
  useAddTenantAdmin,
  useAddTenantProvider,
  useRemoveTenantAdmin,
  useRemoveTenantProvider,
  useTenantMembers,
} from './hooks';
import type { Tenant, TenantAdmin, TeamProvider } from './model';
import { teamProviderColumns } from './teamColumns';

interface TenantMembersDialogProps {
  tenant: Tenant;
  onClose: () => void;
}

type Removal = { kind: 'admin'; admin: TenantAdmin } | { kind: 'provider'; provider: TeamProvider };

/**
 * A Tenant's administrators and providers, each added by mobile number and
 * removable (Requirement MT-12.3). The Requirement MT-2 and MT-3 errors (no such
 * account, admin of another Tenant, provider in another Tenant) are shown in
 * plain language under the form that caused them.
 */
export function TenantMembersDialog({ tenant, onClose }: TenantMembersDialogProps) {
  // Phones get the whole screen: a dialog this dense is unusable as a 390 px card.
  const phone = useMediaQuery((theme: Theme) => theme.breakpoints.down('sm'));
  const [tab, setTab] = useState<'admins' | 'providers'>('admins');
  const [removal, setRemoval] = useState<Removal | null>(null);
  const membersQuery = useTenantMembers(tenant.id);
  const addAdmin = useAddTenantAdmin(tenant.id);
  const addProvider = useAddTenantProvider(tenant.id);
  const removeAdmin = useRemoveTenantAdmin(tenant.id);
  const removeProvider = useRemoveTenantProvider(tenant.id);
  const remove = removal?.kind === 'admin' ? removeAdmin : removeProvider;

  const admins = membersQuery.data?.admins ?? [];
  const providers = membersQuery.data?.providers ?? [];

  const adminColumns: Column<TenantAdmin>[] = [
    {
      key: 'mobile',
      header: 'Mobile number',
      render: (row) => (row.mobileNumber ? formatMobileNumber(row.mobileNumber) : '—'),
    },
    {
      key: 'user',
      header: 'Account',
      render: (row) => (
        <Typography variant="caption" color="text.secondary">
          {shortId(row.userId)}
        </Typography>
      ),
    },
    {
      key: 'action',
      header: '',
      align: 'right',
      render: (row) => (
        <Button
          size="small"
          color="error"
          variant="outlined"
          onClick={() => {
            removeAdmin.reset();
            setRemoval({ kind: 'admin', admin: row });
          }}
        >
          Remove
        </Button>
      ),
    },
  ];

  const providerColumns = teamProviderColumns((provider) => {
    removeProvider.reset();
    setRemoval({ kind: 'provider', provider });
  });

  const confirmRemoval = () => {
    if (!removal) return;
    const done = { onSuccess: () => setRemoval(null) };
    if (removal.kind === 'admin') removeAdmin.mutate(removal.admin.userId, done);
    else removeProvider.mutate(removal.provider.providerId, done);
  };

  return (
    <Dialog open onClose={onClose} maxWidth="md" fullWidth fullScreen={phone}>
      <DialogTitle>
        {tenant.name}
        <Typography variant="body2" color="text.secondary">
          Administrators run the agency&apos;s portal; providers are the team it assigns to jobs.
        </Typography>
      </DialogTitle>
      <Box sx={{ borderBottom: 1, borderColor: 'divider', px: 3 }}>
        <Tabs value={tab} onChange={(_, value: 'admins' | 'providers') => setTab(value)}>
          <Tab value="admins" label={`Administrators (${admins.length})`} />
          <Tab value="providers" label={`Providers (${providers.length})`} />
        </Tabs>
      </Box>
      <DialogContent sx={{ minHeight: 320 }}>
        {tab === 'admins' ? (
          <Stack spacing={2}>
            <AddByMobileForm
              key="admins"
              label="Administrator's mobile number"
              submitLabel="Add administrator"
              helperText="They must already have a HomeFix account; they get the agency portal at their next sign-in."
              onAdd={(mobile) => addAdmin.mutateAsync(mobile)}
              isPending={addAdmin.isPending}
              error={addAdmin.error}
            />
            <QueryStateView
              isLoading={membersQuery.isLoading}
              isError={membersQuery.isError}
              error={membersQuery.error}
              onRetry={() => void membersQuery.refetch()}
              isEmpty={admins.length === 0}
              emptyMessage="No administrators yet. Add one so the agency can work its requests."
            >
              <DataTable columns={adminColumns} rows={admins} rowKey={(row) => row.userId} />
            </QueryStateView>
          </Stack>
        ) : (
          <Stack spacing={2}>
            <AddByMobileForm
              key="providers"
              label="Provider's mobile number"
              submitLabel="Add provider"
              helperText="They must have signed up in the provider app with a provider profile."
              onAdd={(mobile) => addProvider.mutateAsync(mobile)}
              isPending={addProvider.isPending}
              error={addProvider.error}
            />
            <QueryStateView
              isLoading={membersQuery.isLoading}
              isError={membersQuery.isError}
              error={membersQuery.error}
              onRetry={() => void membersQuery.refetch()}
              isEmpty={providers.length === 0}
              emptyMessage="No providers in this agency yet."
            >
              <DataTable
                columns={providerColumns}
                rows={providers}
                rowKey={(row) => row.providerId}
              />
            </QueryStateView>
          </Stack>
        )}
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Done</Button>
      </DialogActions>

      {removal ? (
        <ConfirmRemoveDialog
          title={removal.kind === 'admin' ? 'Remove administrator?' : 'Remove provider?'}
          message={
            removal.kind === 'admin'
              ? `${removal.admin.mobileNumber ? formatMobileNumber(removal.admin.mobileNumber) : 'This person'} loses access to ${tenant.name}'s portal from their next sign-in or token refresh.`
              : `${removal.provider.displayName ?? 'This provider'} leaves ${tenant.name}. Jobs already assigned to them are unaffected.`
          }
          confirmLabel="Remove"
          isPending={remove.isPending}
          error={remove.error}
          onConfirm={confirmRemoval}
          onClose={() => setRemoval(null)}
        />
      ) : null}
    </Dialog>
  );
}
