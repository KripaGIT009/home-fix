import { useRef, useState } from 'react';
import {
  Alert,
  Box,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import { ModuleScreen } from '@components/ModuleScreen';
import { QueryStateView } from '@components/QueryStateView';
import { DataTable, type Column } from '@components/DataTable';
import { StatusChip } from '@components/StatusChip';
import { generateIdempotencyKey } from '@lib/correlation';
import { formatCurrency, formatDateTime, shortId } from '@lib/format';
import { usePayments, useRefundPayment } from './hooks';
import { refundableAmount, type AdminPayment } from './api';

/**
 * Payment and Refund Management module (Requirement 19.2). Lists payment
 * transactions with search and lets an admin issue a full or partial refund up
 * to the remaining refundable amount. Refunds are recorded in the Audit_Log.
 */
export function PaymentManagementScreen() {
  const [searchInput, setSearchInput] = useState('');
  const [search, setSearch] = useState('');
  const [refunding, setRefunding] = useState<AdminPayment | null>(null);

  const paymentsQuery = usePayments(search);

  const columns: Column<AdminPayment>[] = [
    {
      key: 'booking',
      header: 'Booking',
      render: (row) =>
        row.bookingReference ? (
          <Box component="span" title={row.bookingReference} sx={{ fontFamily: 'monospace' }}>
            {shortId(row.bookingReference)}
          </Box>
        ) : (
          '—'
        ),
    },
    { key: 'customer', header: 'Customer', render: (row) => row.customerName ?? '—' },
    { key: 'method', header: 'Method', render: (row) => row.method },
    { key: 'gateway', header: 'Gateway', render: (row) => row.gateway },
    {
      key: 'amount',
      header: 'Amount',
      align: 'right',
      render: (row) => formatCurrency(row.amount, row.currency),
    },
    {
      key: 'refunded',
      header: 'Refunded',
      align: 'right',
      render: (row) =>
        row.refundedAmount > 0 ? formatCurrency(row.refundedAmount, row.currency) : '—',
    },
    { key: 'status', header: 'Status', render: (row) => <StatusChip status={row.status} /> },
    { key: 'created', header: 'Date', render: (row) => formatDateTime(row.createdAt) },
    {
      key: 'action',
      header: '',
      align: 'right',
      render: (row) =>
        refundableAmount(row) > 0 && row.status !== 'PENDING' && row.status !== 'FAILED' ? (
          <Button size="small" variant="outlined" onClick={() => setRefunding(row)}>
            Refund
          </Button>
        ) : null,
    },
  ];

  return (
    <ModuleScreen
      title="Payments & Refunds"
      description="Review payment transactions and issue refunds."
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
          label="Search by booking reference or customer"
          value={searchInput}
          onChange={(event) => setSearchInput(event.target.value)}
          sx={{ maxWidth: 380, flexGrow: 1 }}
        />
        <Box>
          <Button type="submit" variant="contained">
            Search
          </Button>
        </Box>
      </Stack>

      <QueryStateView
        isLoading={paymentsQuery.isLoading}
        isError={paymentsQuery.isError}
        error={paymentsQuery.error}
        onRetry={() => void paymentsQuery.refetch()}
        isEmpty={(paymentsQuery.data?.length ?? 0) === 0}
        emptyMessage="No payments match your search."
      >
        <DataTable columns={columns} rows={paymentsQuery.data ?? []} rowKey={(row) => row.id} />
      </QueryStateView>

      {refunding ? <RefundDialog payment={refunding} onClose={() => setRefunding(null)} /> : null}
    </ModuleScreen>
  );
}

interface RefundDialogProps {
  payment: AdminPayment;
  onClose: () => void;
}

function RefundDialog({ payment, onClose }: RefundDialogProps) {
  const refund = useRefundPayment();
  const max = refundableAmount(payment);
  const [amount, setAmount] = useState<number>(max);
  const [reason, setReason] = useState('');
  const [touched, setTouched] = useState(false);
  // One idempotency key per dialog opening, tied to the submission it was sent
  // with. Retrying the same amount and reason (e.g. after a timeout) reuses it,
  // so the Payment Service can recognise a replay instead of refunding twice;
  // editing either field makes it a different refund, which gets a fresh key.
  const submission = useRef<{ key: string; fingerprint: string | null }>({
    key: generateIdempotencyKey(),
    fingerprint: null,
  });

  const amountInvalid = !(amount > 0 && amount <= max);
  const reasonInvalid = !reason.trim();

  const handleConfirm = () => {
    setTouched(true);
    if (amountInvalid || reasonInvalid) return;
    const payload = { amount, reason: reason.trim() };
    const fingerprint = `${payload.amount}|${payload.reason}`;
    if (submission.current.fingerprint !== null && submission.current.fingerprint !== fingerprint) {
      submission.current.key = generateIdempotencyKey();
    }
    submission.current.fingerprint = fingerprint;
    refund.mutate(
      { id: payment.id, payload, idempotencyKey: submission.current.key },
      { onSuccess: onClose },
    );
  };

  return (
    <Dialog open onClose={onClose} maxWidth="sm" fullWidth>
      <DialogTitle>
        {payment.bookingReference
          ? `Refund booking ${shortId(payment.bookingReference)}`
          : 'Refund payment'}
      </DialogTitle>
      <DialogContent dividers>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
          Refundable up to {formatCurrency(max, payment.currency)}.
        </Typography>
        <Stack spacing={2}>
          <TextField
            label="Refund amount"
            type="number"
            value={amount}
            onChange={(event) =>
              setAmount(event.target.value === '' ? 0 : Number(event.target.value))
            }
            inputProps={{ min: 0.01, max, step: 0.01 }}
            error={touched && amountInvalid}
            helperText={
              touched && amountInvalid
                ? `Enter an amount between 0.01 and ${max.toFixed(2)}.`
                : undefined
            }
            fullWidth
          />
          <TextField
            label="Refund reason"
            value={reason}
            onChange={(event) => setReason(event.target.value)}
            multiline
            minRows={2}
            required
            error={touched && reasonInvalid}
            helperText={touched && reasonInvalid ? 'A reason is required.' : undefined}
            fullWidth
          />
        </Stack>
        {refund.isError ? (
          <Alert severity="error" sx={{ mt: 2 }}>
            {refund.error.message}
          </Alert>
        ) : null}
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose} disabled={refund.isPending}>
          Cancel
        </Button>
        <Button variant="contained" onClick={handleConfirm} disabled={refund.isPending}>
          {refund.isPending ? 'Processing…' : 'Issue refund'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
