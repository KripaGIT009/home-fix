import { useMemo, useState } from 'react';
import { Chip, MenuItem, Stack, TextField, Typography } from '@mui/material';
import { ModuleScreen } from '@components/ModuleScreen';
import { QueryStateView } from '@components/QueryStateView';
import { DataTable, type Column } from '@components/DataTable';
import { StatusChip } from '@components/StatusChip';
import type { BookingStatus } from '@features/bookings/api';
import { formatCurrency, formatDate, formatDateTime, shortId } from '@lib/format';
import { TENANT_BOOKINGS_LIMIT, type TenantBooking } from './api';
import { useTeam, useTenantBookings } from './hooks';
import { ServiceAddress } from './ServiceAddress';

/**
 * The statuses a Tenant's booking can be in once it carries the Tenant —
 * from assignment to payment (or a cancellation/dispute on the way).
 */
const STATUS_OPTIONS: ReadonlyArray<BookingStatus> = [
  'PROVIDER_ASSIGNED',
  'AWAITING_ASSIGNMENT',
  'PROVIDER_ACCEPTED',
  'PROVIDER_ON_THE_WAY',
  'PROVIDER_ARRIVED',
  'JOB_STARTED',
  'JOB_PAUSED',
  'JOB_COMPLETED',
  'PAYMENT_PENDING',
  'PAYMENT_COMPLETED',
  'DISPUTED',
  'CANCELLED',
];

/**
 * Jobs (Requirement MT-8.3): every booking the agency's team is doing or has
 * done — whether it came through the queue or a team member accepted it
 * automatically — newest first, filterable by status. The list is bounded to
 * the latest 200 by booking-service; the screen says so when it hits the cap.
 */
export function JobsScreen() {
  const [status, setStatus] = useState<BookingStatus | ''>('');
  const bookingsQuery = useTenantBookings(status);
  const teamQuery = useTeam();

  const providerNames = useMemo(
    () => new Map((teamQuery.data ?? []).map((p) => [p.providerId, p.displayName])),
    [teamQuery.data],
  );
  const rows = bookingsQuery.data ?? [];

  const columns: Column<TenantBooking>[] = [
    {
      key: 'job',
      header: 'Job',
      render: (row) => (
        <Stack spacing={0.5} alignItems="flex-start" sx={{ minWidth: 140 }}>
          <Typography variant="body2" fontWeight={600}>
            {row.serviceName ?? 'Service'}
          </Typography>
          <Stack direction="row" spacing={0.75} alignItems="center">
            <Typography variant="caption" color="text.secondary" noWrap>
              {row.reference}
            </Typography>
            {row.isEmergency ? (
              <Chip size="small" color="error" label="Emergency" sx={{ height: 20 }} />
            ) : null}
          </Stack>
        </Stack>
      ),
    },
    { key: 'status', header: 'Status', render: (row) => <StatusChip status={row.status} /> },
    {
      key: 'provider',
      header: 'Provider',
      render: (row) => (
        <Typography variant="body2" noWrap>
          {row.providerId
            ? // A Provider who has since left the team is no longer in the list.
              (providerNames.get(row.providerId) ?? shortId(row.providerId))
            : '—'}
        </Typography>
      ),
    },
    {
      key: 'where',
      header: 'Where',
      render: (row) => <ServiceAddress booking={row} minWidth={190} />,
    },
    {
      key: 'when',
      header: 'Wanted',
      render: (row) => (
        <Typography variant="body2" sx={{ minWidth: 110 }}>
          {row.scheduledAt ? formatDateTime(row.scheduledAt) : 'As soon as possible'}
        </Typography>
      ),
    },
    {
      key: 'amount',
      header: 'Amount',
      align: 'right',
      render: (row) => (
        <Typography variant="body2" noWrap>
          {formatCurrency(row.amount, row.currency)}
        </Typography>
      ),
    },
    {
      key: 'created',
      header: 'Booked',
      render: (row) => (
        <Typography variant="body2" noWrap>
          {formatDate(row.createdAt)}
        </Typography>
      ),
    },
  ];

  return (
    <ModuleScreen
      title="Jobs"
      description="Every booking your team is doing or has done, newest first."
    >
      <Stack
        direction={{ xs: 'column', sm: 'row' }}
        spacing={1}
        alignItems={{ xs: 'stretch', sm: 'center' }}
        sx={{ mb: 2 }}
      >
        <TextField
          size="small"
          select
          label="Status"
          value={status}
          onChange={(event) => setStatus(event.target.value as BookingStatus | '')}
          sx={{ minWidth: 220 }}
        >
          <MenuItem value="">All statuses</MenuItem>
          {STATUS_OPTIONS.map((option) => (
            <MenuItem key={option} value={option}>
              {option.replace(/_/g, ' ').toLowerCase()}
            </MenuItem>
          ))}
        </TextField>
        {rows.length >= TENANT_BOOKINGS_LIMIT ? (
          <Typography variant="caption" color="text.secondary">
            Showing the latest {TENANT_BOOKINGS_LIMIT}. Filter by status to find older jobs.
          </Typography>
        ) : null}
      </Stack>

      <QueryStateView
        isLoading={bookingsQuery.isLoading}
        isError={bookingsQuery.isError}
        error={bookingsQuery.error}
        onRetry={() => void bookingsQuery.refetch()}
        isEmpty={rows.length === 0}
        emptyMessage={
          status ? 'No jobs with this status.' : 'No jobs yet. Assigned requests appear here.'
        }
      >
        <DataTable columns={columns} rows={rows} rowKey={(row) => row.id} />
      </QueryStateView>
    </ModuleScreen>
  );
}
