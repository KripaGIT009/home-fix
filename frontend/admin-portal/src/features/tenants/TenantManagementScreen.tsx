import { useMemo, useState } from 'react';
import {
  Alert,
  Box,
  Button,
  Link,
  Snackbar,
  Stack,
  Tab,
  Tabs,
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
import { formatDateTime, formatNumber, shortId } from '@lib/format';
import { ApplicationDecisionDialog } from './ApplicationDecisionDialog';
import { useCatalogCategories, useTenants } from './hooks';
import { formatCoordinates, isApplication, mapLink, type Tenant } from './model';
import { TenantFormDialog } from './TenantFormDialog';
import { TenantMembersDialog } from './TenantMembersDialog';

type Editing = { mode: 'create' } | { mode: 'edit'; tenant: Tenant };

type Decision = { tenant: Tenant; decision: 'approve' | 'reject' };

/** All agencies, or only the applications awaiting a decision (email-auth Requirement 5.6). */
type View = 'all' | 'pending';

/**
 * Tenants module (Requirement MT-12): the service agencies that take over
 * bookings automatic matching could not place. Platform administrators list
 * them with status, coverage and team size, create and edit them, and manage
 * their administrators and providers — onboarding an agency end to end without
 * database access.
 *
 * Agencies can also apply for themselves (email-auth Requirement 5). Their
 * applications wait under "Pending applications", fetched with the status
 * filter, where an admin approves them or rejects them with a reason. An
 * application has no administrators until it is approved, so it offers no
 * Members action.
 */
export function TenantManagementScreen() {
  const [view, setView] = useState<View>('all');
  const allQuery = useTenants();
  const pendingQuery = useTenants('PENDING_APPROVAL');
  const tenantsQuery = view === 'pending' ? pendingQuery : allQuery;
  const categoriesQuery = useCatalogCategories();
  const [filter, setFilter] = useState('');
  const [editing, setEditing] = useState<Editing | null>(null);
  const [members, setMembers] = useState<Tenant | null>(null);
  const [deciding, setDeciding] = useState<Decision | null>(null);
  const [saved, setSaved] = useState<string | null>(null);
  const pendingCount = pendingQuery.data?.length;

  const categoryNames = useMemo(
    () => new Map((categoriesQuery.data ?? []).map((c) => [c.id, c.name])),
    [categoriesQuery.data],
  );

  const rows = useMemo(() => {
    const term = filter.trim().toLowerCase();
    const all = tenantsQuery.data ?? [];
    return term ? all.filter((t) => t.name.toLowerCase().includes(term)) : all;
  }, [tenantsQuery.data, filter]);

  const agencyColumn: Column<Tenant> = {
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
  };

  const coverageColumn: Column<Tenant> = {
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
  };

  const editButton = (row: Tenant) => (
    <Button
      size="small"
      variant="outlined"
      disabled={!categoriesQuery.data}
      onClick={() => setEditing({ mode: 'edit', tenant: row })}
    >
      Edit
    </Button>
  );

  const columns: Column<Tenant>[] = [
    agencyColumn,
    {
      key: 'status',
      header: 'Status',
      // A rejected application carries the reason the applicant was given.
      render: (row) =>
        row.status === 'REJECTED' && row.rejectionReason ? (
          <Tooltip title={`Reason: ${row.rejectionReason}`}>
            <Box component="span">
              <StatusChip status={row.status} />
            </Box>
          </Tooltip>
        ) : (
          <StatusChip status={row.status} />
        ),
    },
    coverageColumn,
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
        row.adminCount === 0 && !isApplication(row.status) ? (
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
          {isApplication(row.status) ? null : (
            <Button size="small" variant="outlined" onClick={() => setMembers(row)}>
              Members
            </Button>
          )}
          {editButton(row)}
        </Stack>
      ),
    },
  ];

  const pendingColumns: Column<Tenant>[] = [
    agencyColumn,
    coverageColumn,
    {
      key: 'applicant',
      header: 'Applicant',
      render: (row) => (
        <Typography variant="caption" color="text.secondary">
          {row.applicantUserId ? shortId(row.applicantUserId) : '—'}
        </Typography>
      ),
    },
    { key: 'applied', header: 'Applied', render: (row) => formatDateTime(row.createdAt) },
    {
      key: 'action',
      header: '',
      align: 'right',
      render: (row) => (
        <Stack direction="row" spacing={1} justifyContent="flex-end">
          {editButton(row)}
          <Button
            size="small"
            color="error"
            variant="outlined"
            onClick={() => setDeciding({ tenant: row, decision: 'reject' })}
          >
            Reject
          </Button>
          <Button
            size="small"
            variant="contained"
            onClick={() => setDeciding({ tenant: row, decision: 'approve' })}
          >
            Approve
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

      <Box sx={{ borderBottom: 1, borderColor: 'divider', mb: 2 }}>
        <Tabs value={view} onChange={(_, next: View) => setView(next)}>
          <Tab value="all" label="All agencies" />
          <Tab
            value="pending"
            label={pendingCount ? `Pending applications (${pendingCount})` : 'Pending applications'}
          />
        </Tabs>
      </Box>

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
          filter
            ? 'No tenant matches that name.'
            : view === 'pending'
              ? 'No agency applications are waiting for a decision.'
              : 'No tenants yet. Create the first agency.'
        }
      >
        <DataTable
          columns={view === 'pending' ? pendingColumns : columns}
          rows={rows}
          rowKey={(row) => row.id}
        />
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

      {deciding ? (
        <ApplicationDecisionDialog
          tenant={deciding.tenant}
          decision={deciding.decision}
          onClose={() => setDeciding(null)}
          onDecided={(message) => {
            setDeciding(null);
            setSaved(message);
          }}
        />
      ) : null}

      <Snackbar
        open={saved !== null}
        autoHideDuration={4000}
        onClose={() => setSaved(null)}
        message={saved}
      />
    </ModuleScreen>
  );
}
