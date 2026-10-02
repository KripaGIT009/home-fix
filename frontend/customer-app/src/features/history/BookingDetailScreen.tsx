import { useCallback, type ReactNode } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import {
  Alert,
  Avatar,
  Box,
  Button,
  Card,
  CardContent,
  Divider,
  Link,
  Stack,
  Typography,
} from '@mui/material';
import DownloadRoundedIcon from '@mui/icons-material/DownloadRounded';
import VerifiedRoundedIcon from '@mui/icons-material/VerifiedRounded';
import StarRounded from '@mui/icons-material/StarRounded';
import EventRoundedIcon from '@mui/icons-material/EventRounded';
import PlaceRoundedIcon from '@mui/icons-material/PlaceRounded';
import NotesRoundedIcon from '@mui/icons-material/NotesRounded';
import TagRoundedIcon from '@mui/icons-material/TagRounded';
import BoltRoundedIcon from '@mui/icons-material/BoltRounded';
import NearMeRoundedIcon from '@mui/icons-material/NearMeRounded';
import ReplayRoundedIcon from '@mui/icons-material/ReplayRounded';
import SearchOffRoundedIcon from '@mui/icons-material/SearchOffRounded';
import HandymanRoundedIcon from '@mui/icons-material/HandymanRounded';
import { Link as RouterLink } from 'react-router-dom';
import { AppShell } from '@components/AppShell';
import { QueryStateView } from '@components/QueryStateView';
import { EmptyState, IconTile } from '@components/StateViews';
import { ProgressTimeline } from '@components/ProgressTimeline';
import { isApiError } from '@api/client';
import { formatCurrency, formatDateTime } from '@lib/format';
import { brand, radius } from '@lib/theme';
import { CategoryIcon } from '@features/catalog/categoryIcon';
import { categoryArt } from '@features/catalog/categoryArt';
import { ChatButton } from '@features/tracking/ChatButton';
import { CHAT_STATUSES, journeySteps } from '@features/tracking/progress';
import type { BookingDetail } from './api';
import { useBookingDetail, useInvoiceDownload } from './hooks';
import { StatusChip } from './StatusChip';
import { isTerminalStatus } from './status';
import { useBookingService } from './useServiceName';

/**
 * Booking Detail screen (Requirement 28.7). Reached from Service History. Shows
 * the booking, its progress, and — when an invoice exists — lets the Customer
 * download the PDF via a freshly minted signed URL (Requirement 13.3). Active
 * bookings also link to live tracking and the in-app chat.
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

  const notFound = query.isError && (query.error.status === 404 || query.error.status === 403);

  return (
    <AppShell
      title="Booking details"
      {...(query.data ? { subtitle: `Reference ${query.data.referenceNumber}` } : {})}
      onBack={() => navigate('/history')}
      width="full"
    >
      {notFound ? (
        <BookingNotFound onBack={() => navigate('/history')} />
      ) : (
        <QueryStateView
          isLoading={query.isLoading}
          isError={query.isError}
          error={query.error}
          onRetry={() => void query.refetch()}
          isEmpty={!query.data}
          emptyMessage="We couldn't find this booking."
          skeletonRows={3}
          skeletonHeight={120}
        >
          {query.data ? (
            <BookingDetailBody
              detail={query.data}
              onTrack={() => navigate(`/bookings/${bookingId}/track`)}
              onChat={() => navigate(`/bookings/${bookingId}/chat`)}
              onBookAgain={(subcategoryId) => navigate(`/book/${subcategoryId}`)}
              onDownloadInvoice={handleDownloadInvoice}
              invoicePending={invoiceDownload.isPending}
              invoiceError={invoiceError}
            />
          ) : null}
        </QueryStateView>
      )}
    </AppShell>
  );
}

/** Friendly dead end for a booking that does not exist or is not the caller's. */
export function BookingNotFound({ onBack }: { onBack: () => void }) {
  return (
    <EmptyState
      icon={<SearchOffRoundedIcon />}
      title="We couldn't find that booking"
      description="It may have been removed, or it belongs to a different account."
      action={
        <Button variant="contained" size="large" onClick={onBack}>
          Go to your bookings
        </Button>
      }
    />
  );
}

function BookingDetailBody({
  detail,
  onTrack,
  onChat,
  onBookAgain,
  onDownloadInvoice,
  invoicePending,
  invoiceError,
}: {
  detail: BookingDetail;
  onTrack: () => void;
  onChat: () => void;
  onBookAgain: (subcategoryId: string) => void;
  onDownloadInvoice: (invoiceId: string) => void;
  invoicePending: boolean;
  invoiceError: string | null;
}) {
  const { name, category } = useBookingService(detail.serviceName, detail.subcategoryId);
  const art = categoryArt(category?.icon, category?.name);
  const terminal = isTerminalStatus(detail.status);
  const canChat = CHAT_STATUSES.includes(detail.status);
  const when = detail.scheduledAt ?? (detail.emergency ? undefined : detail.date);

  return (
    <Box
      sx={{
        display: 'grid',
        gridTemplateColumns: { xs: '1fr', md: 'minmax(0, 1fr) 360px' },
        gridTemplateAreas: {
          xs: '"summary" "actions" "progress"',
          md: '"summary actions" "progress actions"',
        },
        gap: { xs: 2, md: 3 },
        alignItems: 'start',
      }}
    >
      <Card sx={{ gridArea: 'summary' }}>
        <CardContent>
          <Stack direction="row" spacing={2} alignItems="flex-start">
            <IconTile size={52} bg={art.wash} color={art.accent}>
              {category ? (
                <CategoryIcon iconKey={category.icon} categoryName={category.name} />
              ) : (
                <HandymanRoundedIcon />
              )}
            </IconTile>
            <Box sx={{ minWidth: 0, flexGrow: 1 }}>
              <Typography variant="h4" component="h2">
                {name ?? 'Your booking'}
              </Typography>
              <Stack
                direction="row"
                spacing={1}
                alignItems="center"
                flexWrap="wrap"
                useFlexGap
                sx={{ mt: 1 }}
              >
                <StatusChip status={detail.status} size="medium" />
                {detail.emergency ? <EmergencyTag /> : null}
              </Stack>
            </Box>
          </Stack>

          <Divider sx={{ my: { xs: 2, md: 2.5 } }} />

          <Stack spacing={2}>
            <DetailRow
              icon={<EventRoundedIcon />}
              label={detail.emergency && !detail.scheduledAt ? 'Requested' : 'Scheduled for'}
              value={when ? formatDateTime(when) : 'As soon as possible'}
            />
            {detail.createdAt ? (
              <DetailRow
                icon={<EventRoundedIcon />}
                label="Booked on"
                value={formatDateTime(detail.createdAt)}
              />
            ) : null}
            <DetailRow icon={<TagRoundedIcon />} label="Reference" value={detail.referenceNumber} />
            {detail.address ? (
              <DetailRow icon={<PlaceRoundedIcon />} label="Address" value={detail.address} />
            ) : null}
            {detail.description ? (
              <DetailRow
                icon={<NotesRoundedIcon />}
                label="Your notes"
                value={detail.description}
              />
            ) : null}
          </Stack>

          {detail.provider ? (
            <>
              <Divider sx={{ my: { xs: 2, md: 2.5 } }} />
              <ProviderRow provider={detail.provider} />
            </>
          ) : null}
        </CardContent>
      </Card>

      <Card sx={{ gridArea: 'actions', position: { md: 'sticky' }, top: { md: 96 } }}>
        <CardContent>
          <Typography variant="body2" color="text.secondary">
            {terminal ? 'Amount' : 'Estimated total'}
          </Typography>
          <Typography variant="h2" component="p" sx={{ mt: 0.25 }}>
            {formatCurrency(detail.amount, detail.currency)}
          </Typography>
          {terminal ? null : (
            <Typography variant="caption" color="text.secondary" display="block" sx={{ mt: 0.5 }}>
              You approve any change before it is charged.
            </Typography>
          )}

          <Stack spacing={1.25} sx={{ mt: 2.5 }}>
            {terminal ? null : (
              <Button
                variant="contained"
                size="large"
                fullWidth
                startIcon={<NearMeRoundedIcon />}
                onClick={onTrack}
              >
                Track booking
              </Button>
            )}
            {canChat ? <ChatButton bookingId={detail.bookingId} onOpen={onChat} /> : null}

            {detail.invoice ? (
              <>
                {invoiceError ? <Alert severity="error">{invoiceError}</Alert> : null}
                <Button
                  variant={terminal ? 'contained' : 'outlined'}
                  fullWidth
                  size="large"
                  startIcon={<DownloadRoundedIcon />}
                  onClick={() => detail.invoice && onDownloadInvoice(detail.invoice.invoiceId)}
                  disabled={invoicePending}
                  aria-label={`Download invoice ${detail.invoice.invoiceNumber}`}
                >
                  {invoicePending ? 'Preparing…' : 'Download invoice'}
                </Button>
              </>
            ) : null}

            {terminal && detail.subcategoryId ? (
              <Button
                variant={detail.invoice ? 'outlined' : 'contained'}
                size="large"
                fullWidth
                startIcon={<ReplayRoundedIcon />}
                onClick={() => detail.subcategoryId && onBookAgain(detail.subcategoryId)}
              >
                Book again
              </Button>
            ) : null}
          </Stack>

          <Typography variant="body2" color="text.secondary" sx={{ mt: 2.5 }}>
            Something not right?{' '}
            <Link component={RouterLink} to="/help" fontWeight={600} underline="hover">
              Get help
            </Link>
          </Typography>
        </CardContent>
      </Card>

      <Card sx={{ gridArea: 'progress' }}>
        <CardContent>
          <Typography variant="h5" component="h2" sx={{ mb: 2.5 }}>
            Progress
          </Typography>
          <ProgressTimeline steps={journeySteps(detail.status)} />
        </CardContent>
      </Card>
    </Box>
  );
}

function EmergencyTag() {
  return (
    <Stack
      direction="row"
      spacing={0.5}
      alignItems="center"
      sx={{
        px: 1.25,
        py: 0.5,
        borderRadius: `${radius.pill}px`,
        bgcolor: brand.redSoft,
        color: brand.redDark,
      }}
    >
      <BoltRoundedIcon sx={{ fontSize: 15 }} aria-hidden />
      <Typography variant="caption" fontWeight={700}>
        Emergency
      </Typography>
    </Stack>
  );
}

function DetailRow({ icon, label, value }: { icon: ReactNode; label: string; value: string }) {
  return (
    <Stack direction="row" spacing={1.5} alignItems="flex-start">
      <Box
        sx={{ color: 'text.secondary', display: 'flex', pt: 0.25, '& svg': { fontSize: 20 } }}
        aria-hidden
      >
        {icon}
      </Box>
      <Box sx={{ minWidth: 0 }}>
        <Typography variant="caption" color="text.secondary" display="block">
          {label}
        </Typography>
        <Typography variant="body1" fontWeight={600} sx={{ wordBreak: 'break-word' }}>
          {value}
        </Typography>
      </Box>
    </Stack>
  );
}

function ProviderRow({ provider }: { provider: NonNullable<BookingDetail['provider']> }) {
  const initials = provider.displayName
    .split(' ')
    .map((part) => part.charAt(0))
    .slice(0, 2)
    .join('')
    .toUpperCase();
  return (
    <Stack direction="row" spacing={1.5} alignItems="center">
      <Avatar
        aria-hidden
        sx={{ width: 44, height: 44, bgcolor: brand.accentSoft, color: 'primary.main' }}
      >
        {initials}
      </Avatar>
      <Box sx={{ flexGrow: 1, minWidth: 0 }}>
        <Typography variant="caption" color="text.secondary" display="block">
          Your professional
        </Typography>
        <Stack direction="row" spacing={0.5} alignItems="center">
          <Typography variant="subtitle1" fontWeight={700} noWrap>
            {provider.displayName}
          </Typography>
          {provider.verified ? (
            <VerifiedRoundedIcon
              sx={{ fontSize: 18, color: 'success.main' }}
              aria-label="Verified"
            />
          ) : null}
        </Stack>
      </Box>
      <Stack direction="row" spacing={0.25} alignItems="center">
        <StarRounded sx={{ fontSize: 18, color: brand.warm }} aria-hidden />
        <Typography variant="body2" fontWeight={700}>
          {provider.rating.toFixed(1)}
        </Typography>
      </Stack>
    </Stack>
  );
}
