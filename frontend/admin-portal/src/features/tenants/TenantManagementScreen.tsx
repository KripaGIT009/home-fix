import { useMemo, useState } from 'react';
import {
  Alert,
  Button,
  Link,
  Snackbar,
  Stack,
  TextField,
  Tooltip,
  Typography,
} from '@mui/material';
import AddRoundedIcon from '@mui/icons-material/AddRounded';
import { ModuleScreen } from '@components/ModuleScreen';
import { QueryStateView } from '@components/QueryStateView';
import { DataTable, type Column } from '@components/DataTable';
import { StatusChip } from '@components/StatusChip';
import { formatMobileNumber } from '@features/auth/phone';
import { formatDateTime, formatNumber } from '@lib/format';
import { useCatalogCategories, useTenants } from './hooks';
import { formatCoordinates, mapLink, type Tenant } from './model';
import { TenantFormDialog } from './TenantFormDialog';
import { TenantMembersDialog } from './TenantMembersDialog';

type Editing = { mode: 'create' } | { mode: 'edit'; tenant: Tenant };

/**
 * Tenants module (Requirement MT-12): the service agencies that take over
 * bookings automatic matching could not place. Platform administrators list
 * them with status, coverage and team size, create and edit them, and manage
 * their administrators and providers — onboarding an agency end to end without
 * database access.
 */
export function TenantManagementScreen() {
  const tenantsQuery = useTenants();
  const categoriesQuery = useCatalogCategories();
  const [filter, setFilter] = useState('');
  const [editing, setEditing] = useState<Editing | null>(null);
  const [members, setMembers] = useState<Tenant | null>(null);
  const [saved, setSaved] = useState<string | null>(null);

  const categoryNames = useMemo(
    () => new Map((categoriesQuery.data ?? []).map((c) => [c.id, c.name])),
    [categoriesQuery.data],
  );

  const rows = useMemo(() => {
    const term = filter.trim().toLowerCase();
    const all = tenantsQuery.data ?? [];
    return term ? all.filter((t) => t.name.toLowerCase().includes(term)) : all;
  }, [tenantsQuery.data, filter]);

  const columns: Column<Tenant>[] = [
    {
      key: 'name',
      header: 'Agency',
      render: (row) => (
        <Stack spacing={0.25}>
          <Typography variant="body2" fontWeight={600}>
            {row.name}
          </Typography>
          {row.contactPhone || row.contactEmail ? (
            <Typography variant="caption" color="text.secondary">
              {[row.contactPhone ? formatMobileNumber(row.contactPhone) : null, row.contactEmail]
                .filter(Boolean)
                .join(' · ')}
            </Typography>
          ) : null}
        </Stack>
      ),
    },
    { key: 'status', header: 'Status', render: (row) => <StatusChip status={row.status} /> },
    {
      key: 'coverage',
      header: 'Coverage',
      render: (row) => {
        const names = row.categoryIds.map((id) => categoryNames.get(id) ?? 'Unknown category');
        return (
          <Stack spacing={0.25}>
            <Typography variant="body2">
              {row.serviceRadiusKm} km around{' '}
              <Link
                href={mapLink(row.baseLatitude, row.baseLongitude)}
                target="_blank"
                rel="noopener noreferrer"
              >
                {formatCoordinates(row.baseLatitude, row.baseLongitude)}
              </Link>
            </Typography>
            <Tooltip title={names.join(', ')}>
              <Typography variant="caption" color="text.secondary" sx={{ maxWidth: 280 }} noWrap>
                {names.length <= 3
                  ? names.join(', ')
                  : `${names.slice(0, 2).join(', ')} +${names.length - 2} more`}
              </Typography>
            </Tooltip>
          </Stack>
        );
      },
    },
    {
      key: 'providers',
      header: 'Providers',
      align: 'right',
      render: (row) => formatNumber(row.providerCount),
    },
    {
      key: 'admins',
      header: 'Admins',
      align: 'right',
      render: (row) =>
        row.adminCount === 0 ? (
          <Tooltip title="No administrator: nobody can work this agency's requests.">
            <Typography variant="body2" color="warning.main" fontWeight={700}>
              0
            </Typography>
          </Tooltip>
        ) : (
          formatNumber(row.adminCount)
        ),
    },
    { key: 'updated', header: 'Updated', render: (row) => formatDateTime(row.updatedAt) },
    {
      key: 'action',
      header: '',
      align: 'right',
      render: (row) => (
        <Stack direction="row" spacing={1} justifyContent="flex-end">
          <Button size="small" variant="outlined" onClick={() => setMembers(row)}>
            Members
          </Button>
          <Button
            size="small"
            variant="outlined"
            disabled={!categoriesQuery.data}
            onClick={() => setEditing({ mode: 'edit', tenant: row })}
          >
            Edit
          </Button>
        </Stack>
      ),
    },
  ];

  return (
    <ModuleScreen
      title="Tenants"
      description="Service agencies that take over requests no provider accepted automatically."
      actions={
        <Button
          variant="contained"
          startIcon={<AddRoundedIcon />}
          disabled={!categoriesQuery.data}
          onClick={() => setEditing({ mode: 'create' })}
        >
          New tenant
        </Button>
      }
    >
      {categoriesQuery.isError ? (
        <Alert severity="warning" sx={{ mb: 2 }}>
          The service catalog could not be loaded, so tenants cannot be created or edited right now.{' '}
          {categoriesQuery.error.message}
        </Alert>
      ) : null}

      <TextField
        size="small"
        label="Filter by name"
        value={filter}
        onChange={(event) => setFilter(event.target.value)}
        sx={{ mb: 2, width: { xs: '100%', sm: 320 } }}
      />

      <QueryStateView
        isLoading={tenantsQuery.isLoading}
        isError={tenantsQuery.isError}
        error={tenantsQuery.error}
        onRetry={() => void tenantsQuery.refetch()}
        isEmpty={rows.length === 0}
        emptyMessage={
          filter ? 'No tenant matches that name.' : 'No tenants yet. Create the first agency.'
        }
      >
        <DataTable columns={columns} rows={rows} rowKey={(row) => row.id} />
      </QueryStateView>

      {editing && categoriesQuery.data ? (
        <TenantFormDialog
          {...(editing.mode === 'edit' ? { tenant: editing.tenant } : {})}
          categories={categoriesQuery.data}
          onClose={() => setEditing(null)}
          onSaved={(tenant) => {
            setEditing(null);
            setSaved(editing.mode === 'edit' ? `${tenant.name} saved` : `${tenant.name} created`);
          }}
        />
      ) : null}

      {members ? <TenantMembersDialog tenant={members} onClose={() => setMembers(null)} /> : null}

      <Snackbar
        open={saved !== null}
        autoHideDuration={4000}
        onClose={() => setSaved(null)}
        message={saved}
      />
    </ModuleScreen>
  );
}
