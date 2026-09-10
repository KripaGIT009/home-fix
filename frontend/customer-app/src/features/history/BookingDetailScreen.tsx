import { useCallback } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import {
  Alert,
  Box,
  Button,
  Card,
  CardContent,
  Chip,
  Divider,
  Stack,
  Typography,
} from '@mui/material';
import DownloadRoundedIcon from '@mui/icons-material/DownloadRounded';
import VerifiedRoundedIcon from '@mui/icons-material/VerifiedRounded';
import { AppShell } from '@components/AppShell';
import { QueryStateView } from '@components/QueryStateView';
import { isApiError } from '@api/client';
import { formatCurrency, formatDateTime } from '@lib/format';
import { TRACKABLE_STATUSES } from '@features/tracking/constants';
import { ChatButton } from '@features/tracking/ChatButton';
import type { BookingDetail } from './api';
import { useBookingDetail, useInvoiceDownload } from './hooks';
import { bookingStatusColor, bookingStatusLabel } from './status';

/**
 * Booking Detail screen (Requirement 28.7). Reached from Service History. Shows
 * the full booking, and when an invoice exists lets the Customer download the
 * PDF via a freshly minted signed URL (Requirement 13.3). Active bookings also
 * expose the in-app chat and a link to live tracking.
 */
export function BookingDetailScreen() {
  const { bookingId = '' } = useParams();
  const navigate = useNavigate();

  const query = useBookingDetail(bookingId);
  const invoiceDownload = useInvoiceDownload();

  const invoiceMutate = invoiceDownload.mutate;
  const handleDownloadInvoice = useCallback(
    (invoiceId: string) => {
      invoiceMutate(invoiceId, {
        onSuccess: ({ url }) => {
          // Open the signed URL so the browser renders/downloads the PDF
          // (Task 33 acceptance: "invoice download opens PDF").
          window.open(url, '_blank', 'noopener,noreferrer');
        },
      });
    },
    [invoiceMutate],
  );

  const invoiceError =
    invoiceDownload.isError && isApiError(invoiceDownload.error)
      ? invoiceDownload.error.message
      : null;

  return (
    <AppShell title="Booking details" onBack={() => navigate('/history')}>
      <QueryStateView
        isLoading={query.isLoading}
        isError={query.isError}
        error={query.error}
        onRetry={() => void query.refetch()}
        isEmpty={!query.data}
        emptyMessage="We couldn't find this booking."
      >
        {query.data ? (
          <BookingDetailBody
            detail={query.data}
            onTrack={() => navigate(`/bookings/${bookingId}/track`)}
            onChat={() => navigate(`/bookings/${bookingId}/chat`)}
            onDownloadInvoice={handleDownloadInvoice}
            invoicePending={invoiceDownload.isPending}
            invoiceError={invoiceError}
          />
        ) : null}
      </QueryStateView>
    </AppShell>
  );
}

function BookingDetailBody({
  detail,
  onTrack,
  onChat,
  onDownloadInvoice,
  invoicePending,
  invoiceError,
}: {
  detail: BookingDetail;
  onTrack: () => void;
  onChat: () => void;
  onDownloadInvoice: (invoiceId: string) => void;
  invoicePending: boolean;
  invoiceError: string | null;
}) {
  const isActive = (TRACKABLE_STATUSES as readonly string[]).includes(detail.status);

  return (
    <Stack spacing={2}>
      <Box>
        <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap">
          <Typography variant="h5" component="h1" fontWeight={700}>
            {detail.serviceName}
          </Typography>
          <Chip
            size="small"
            color={bookingStatusColor(detail.status)}
            label={bookingStatusLabel(detail.status)}
          />
        </Stack>
        <Typography variant="body2" color="text.secondary">
          {detail.referenceNumber} · {formatDateTime(detail.date)}
        </Typography>
      </Box>

      <Card>
        <CardContent>
          <Stack spacing={1.5}>
            <Row label="Amount" value={formatCurrency(detail.amount, detail.currency)} strong />
            {detail.address ? <Row label="Address" value={detail.address} /> : null}
            {detail.description ? <Row label="Description" value={detail.description} /> : null}
            {detail.provider ? (
              <Stack direction="row" justifyContent="space-between" alignItems="center">
                <Typography variant="body2" color="text.secondary">
                  Provider
                </Typography>
                <Stack direction="row" spacing={0.75} alignItems="center">
                  <Typography variant="body2">{detail.provider.displayName}</Typography>
                  {detail.provider.verified ? (
                    <VerifiedRoundedIcon fontSize="small" color="success" aria-label="Verified" />
                  ) : null}
                </Stack>
              </Stack>
            ) : null}
          </Stack>
        </CardContent>
      </Card>

      {detail.invoice ? (
        <Box>
          {invoiceError ? (
            <Alert severity="error" sx={{ mb: 1 }}>
              {invoiceError}
            </Alert>
          ) : null}
          <Button
            variant="contained"
            fullWidth
            size="large"
            startIcon={<DownloadRoundedIcon />}
            onClick={() => detail.invoice && onDownloadInvoice(detail.invoice.invoiceId)}
            disabled={invoicePending}
            aria-label={`Download invoice ${detail.invoice.invoiceNumber}`}
          >
            {invoicePending ? 'Preparing…' : `Download invoice ${detail.invoice.invoiceNumber}`}
          </Button>
        </Box>
      ) : null}

      {isActive ? (
        <>
          <Divider />
          <Button variant="outlined" size="large" fullWidth onClick={onTrack}>
            Track live
          </Button>
          <ChatButton bookingId={detail.bookingId} onOpen={onChat} />
        </>
      ) : null}
    </Stack>
  );
}

function Row({ label, value, strong }: { label: string; value: string; strong?: boolean }) {
  return (
    <Stack direction="row" justifyContent="space-between" spacing={2}>
      <Typography variant="body2" color="text.secondary">
        {label}
      </Typography>
      <Typography
        variant={strong ? 'subtitle1' : 'body2'}
        fontWeight={strong ? 700 : 400}
        sx={{ textAlign: 'right' }}
      >
        {value}
      </Typography>
    </Stack>
  );
}
