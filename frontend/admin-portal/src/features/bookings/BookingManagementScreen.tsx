import { useState } from 'react';
import {
  Alert,
  Box,
  Button,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  MenuItem,
  Stack,
  TextField,
} from '@mui/material';
import { ModuleScreen } from '@components/ModuleScreen';
import { QueryStateView } from '@components/QueryStateView';
import { DataTable, type Column } from '@components/DataTable';
import { StatusChip } from '@components/StatusChip';
import { formatCurrency, formatDateTime } from '@lib/format';
import { useBookings, useCancelBooking } from './hooks';
import type { AdminBooking, BookingStatus } from './api';

/** Statuses that cannot be force-cancelled (already terminal). */
const TERMINAL_STATUSES: ReadonlySet<BookingStatus> = new Set<BookingStatus>([
  'JOB_COMPLETED',
  'CUSTOMER_CONFIRMED',
  'PAYMENT_COMPLETED',
  'REFUNDED',
  'CANCELLED',
]);

/** Status options offered in the filter dropdown. */
const STATUS_OPTIONS: ReadonlyArray<BookingStatus> = [
  'CREATED',
  'SEARCHING_PROVIDER',
  'PROVIDER_ASSIGNED',
  'PROVIDER_ACCEPTED',
  'JOB_STARTED',
  'JOB_COMPLETED',
  'PAYMENT_COMPLETED',
  'DISPUTED',
  'REFUNDED',
  'CANCELLED',
];

/**
 * Booking Management module (Requirement 19.2). Lists bookings with search and
 * a status filter, and lets an admin force-cancel an in-flight booking with a
 * reason. Terminal bookings cannot be cancelled.
 */
export function BookingManagementScreen() {
  const [searchInput, setSearchInput] = useState('');
  const [search, setSearch] = useState('');
  const [status, setStatus] = useState<BookingStatus | ''>('');
  const [cancelling, setCancelling] = useState<AdminBooking | null>(null);

  const bookingsQuery = useBookings(search, status);

  const columns: Column<AdminBooking>[] = [
    {
      key: 'reference',
      header: 'Reference',
      render: (row) => (
        <Stack direction="row" spacing={0.5} alignItems="center">
          <span>{row.reference}</span>
          {row.isEmergency ? <Chip size="small" color="error" label="Emergency" /> : null}
        </Stack>
      ),
    },
    { key: 'customer', header: 'Customer', render: (row) => row.customerName ?? '—' },
    { key: 'provider', header: 'Provider', render: (row) => row.providerName ?? '—' },
    { key: 'service', header: 'Service', render: (row) => row.serviceName ?? '—' },
    { key: 'status', header: 'Status', render: (row) => <StatusChip status={row.status} /> },
    {
      key: 'amount',
      header: 'Amount',
      align: 'right',
      render: (row) => formatCurrency(row.totalAmount, row.currency),
    },
    { key: 'created', header: 'Created', render: (row) => formatDateTime(row.createdAt) },
    {
      key: 'action',
      header: '',
      align: 'right',
      render: (row) =>
        TERMINAL_STATUSES.has(row.status) ? null : (
          <Button size="small" color="error" variant="outlined" onClick={() => setCancelling(row)}>
            Cancel
          </Button>
        ),
    },
  ];

  return (
    <ModuleScreen
      title="Booking Management"
      description="Search, inspect, and intervene in bookings across the platform."
    >
      <Stack
        component="form"
        direction={{ xs: 'column', sm: 'row' }}
        spacing={1}
        sx={{ mb: 2 }}
        onSubmit={(event) => {
          event.preventDefault();
          setSearch(searchInput.trim());
        }}
      >
        <TextField
          size="small"
          label="Search by reference, customer, or provider"
          value={searchInput}
          onChange={(event) => setSearchInput(event.target.value)}
          sx={{ maxWidth: 380, flexGrow: 1 }}
        />
        <TextField
          size="small"
          select
          label="Status"
          value={status}
          onChange={(event) => setStatus(event.target.value as BookingStatus | '')}
          sx={{ minWidth: 200 }}
        >
          <MenuItem value="">All statuses</MenuItem>
          {STATUS_OPTIONS.map((option) => (
            <MenuItem key={option} value={option}>
              {option.replace(/_/g, ' ').toLowerCase()}
            </MenuItem>
          ))}
        </TextField>
        <Box>
          <Button type="submit" variant="contained">
            Search
          </Button>
        </Box>
      </Stack>

      <QueryStateView
        isLoading={bookingsQuery.isLoading}
        isError={bookingsQuery.isError}
        error={bookingsQuery.error}
        onRetry={() => void bookingsQuery.refetch()}
        isEmpty={(bookingsQuery.data?.length ?? 0) === 0}
        emptyMessage="No bookings match your filters."
      >
        <DataTable columns={columns} rows={bookingsQuery.data ?? []} rowKey={(row) => row.id} />
      </QueryStateView>

      {cancelling ? (
        <CancelDialog booking={cancelling} onClose={() => setCancelling(null)} />
      ) : null}
    </ModuleScreen>
  );
}

interface CancelDialogProps {
  booking: AdminBooking;
  onClose: () => void;
}

function CancelDialog({ booking, onClose }: CancelDialogProps) {
  const cancel = useCancelBooking();
  const [reason, setReason] = useState('');
  const [touched, setTouched] = useState(false);

  const handleConfirm = () => {
    if (!reason.trim()) {
      setTouched(true);
      return;
    }
    cancel.mutate({ id: booking.id, reason: reason.trim() }, { onSuccess: onClose });
  };

  return (
    <Dialog open onClose={onClose} maxWidth="sm" fullWidth>
      <DialogTitle>Cancel booking {booking.reference}</DialogTitle>
      <DialogContent dividers>
        <TextField
          label="Cancellation reason"
          value={reason}
          onChange={(event) => setReason(event.target.value)}
          fullWidth
          multiline
          minRows={2}
          required
          error={touched && !reason.trim()}
          helperText={
            touched && !reason.trim()
              ? 'A reason is required to cancel a booking.'
              : 'Recorded in the audit log and shared with the parties.'
          }
        />
        {cancel.isError ? (
          <Alert severity="error" sx={{ mt: 2 }}>
            {cancel.error.message}
          </Alert>
        ) : null}
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose} disabled={cancel.isPending}>
          Keep booking
        </Button>
        <Button
          color="error"
          variant="contained"
          onClick={handleConfirm}
          disabled={cancel.isPending}
        >
          {cancel.isPending ? 'Cancelling…' : 'Cancel booking'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
