import { useMemo, type ReactNode } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import {
  Alert,
  Avatar,
  Badge,
  Box,
  Button,
  Card,
  CardContent,
  CircularProgress,
  Stack,
  Typography,
} from '@mui/material';
import { keyframes } from '@mui/material/styles';
import VerifiedRoundedIcon from '@mui/icons-material/VerifiedRounded';
import AccessTimeRoundedIcon from '@mui/icons-material/AccessTimeRounded';
import WarningAmberRoundedIcon from '@mui/icons-material/WarningAmberRounded';
import StarRounded from '@mui/icons-material/StarRounded';
import PersonSearchRoundedIcon from '@mui/icons-material/PersonSearchRounded';
import SearchOffRoundedIcon from '@mui/icons-material/SearchOffRounded';
import HowToRegRoundedIcon from '@mui/icons-material/HowToRegRounded';
import DoorFrontRoundedIcon from '@mui/icons-material/DoorFrontRounded';
import HandymanRoundedIcon from '@mui/icons-material/HandymanRounded';
import PauseCircleRoundedIcon from '@mui/icons-material/PauseCircleRounded';
import RequestQuoteRoundedIcon from '@mui/icons-material/RequestQuoteRounded';
import TaskAltRoundedIcon from '@mui/icons-material/TaskAltRounded';
import PaymentsRoundedIcon from '@mui/icons-material/PaymentsRounded';
import EventBusyRoundedIcon from '@mui/icons-material/EventBusyRounded';
import SupportAgentRoundedIcon from '@mui/icons-material/SupportAgentRounded';
import CurrencyRupeeRoundedIcon from '@mui/icons-material/CurrencyRupeeRounded';
import NearMeRoundedIcon from '@mui/icons-material/NearMeRounded';
import BoltRoundedIcon from '@mui/icons-material/BoltRounded';
import { AppShell } from '@components/AppShell';
import { InlineError, IconTile } from '@components/StateViews';
import { ProgressTimeline } from '@components/ProgressTimeline';
import { friendlyErrorMessage } from '@api/client';
import { formatCurrency, formatDateTime, formatEtaMinutes, formatTimeAgo } from '@lib/format';
import { brand, radius } from '@lib/theme';
import { useBookingStore } from '@stores/bookingStore';
import type { BookingStatus } from '@stores/bookingStore';
import { BookingNotFound } from '@features/history/BookingDetailScreen';
import { useBookingDetail, useQuoteDecision } from '@features/history/hooks';
import { StatusChip } from '@features/history/StatusChip';
import { useBookingService } from '@features/history/useServiceName';
import type { BookingDetail } from '@features/history/api';
import { PaymentPanel } from '@features/payment/PaymentPanel';
import { TrackingMap } from './TrackingMap';
import { StatePanel } from './StatePanel';
import { ChatButton } from './ChatButton';
import { useLiveLocation, useNowTick } from './hooks';
import { STALE_LOCATION_THRESHOLD_MS, TRACKING_TICK_MS } from './constants';
import { CHAT_STATUSES, LOCATION_STATUSES, journeySteps, trackingPhase } from './progress';
import { estimateEtaMinutes, type Coordinates, type LocationSnapshot } from './api';

type Provider = NonNullable<LocationSnapshot['provider']>;

/**
 * Live Tracking screen (Requirement 10, Requirement 28.7).
 *
 * Status-aware: the booking is re-read every few seconds and the screen shows
 * what is actually happening — finding a professional, waiting for them to
 * start the trip, the live map while they travel (seeded from the Location
 * Service snapshot, then streamed over SSE), and the job and payment steps
 * after. A booking with no location yet is a waiting state, never an error.
 * Stale locations (> 60 s) are flagged with the last-updated time
 * (Requirement 10.7), and the in-app chat is one tap away (Requirement 18).
 */
export function LiveTrackingScreen() {
  const { bookingId = '' } = useParams();
  const navigate = useNavigate();

  const detailQuery = useBookingDetail(bookingId, { poll: true });
  const detail = detailQuery.data;
  // The booking just created in this session, used until the first read lands
  // (or if the read endpoint is unreachable).
  const local = useBookingStore((state) =>
    state.activeBooking?.bookingId === bookingId ? state.activeBooking : null,
  );

  const status: BookingStatus | undefined = detail?.status ?? local?.status;
  const reference = detail?.referenceNumber ?? local?.referenceNumber;
  const subcategoryId = detail?.subcategoryId ?? local?.subcategoryId;
  const { name: serviceName } = useBookingService(detail?.serviceName, subcategoryId);

  // Ask for the provider's location only while they share it. If the booking
  // itself cannot be read, still try — the map is better than nothing.
  const locationEnabled = status
    ? LOCATION_STATUSES.includes(status)
    : detailQuery.isError && !isNotFound(detailQuery.error);
  const live = useLiveLocation(bookingId, locationEnabled);

  const notFound = detailQuery.isError && isNotFound(detailQuery.error) && !local;
  const phase = status ? trackingPhase(status) : undefined;
  const showLive = phase === 'enRoute' || (!status && live.snapshot !== undefined);

  const goChat = () => navigate(`/bookings/${bookingId}/chat`);
  const goDetails = () => navigate(`/bookings/${bookingId}`);
  const bookAgain = () => navigate(subcategoryId ? `/book/${subcategoryId}` : '/home');

  const provider: Provider | undefined = live.snapshot?.provider ?? detail?.provider;

  let body: ReactNode;
  if (notFound) {
    body = <BookingNotFound onBack={() => navigate('/history')} />;
  } else if (!status && detailQuery.isLoading) {
    body = (
      <Stack alignItems="center" sx={{ py: 10 }}>
        <CircularProgress aria-label="Loading your booking" />
      </Stack>
    );
  } else if (!status && !live.snapshot) {
    body = (
      <InlineError
        error={detailQuery.error}
        title="We couldn't load this booking"
        onRetry={() => void detailQuery.refetch()}
      />
    );
  } else {
    body = (
      <Box
        sx={{
          display: 'grid',
          gridTemplateColumns: { xs: '1fr', md: 'minmax(0, 1fr) 360px' },
          gap: { xs: 2, md: 3 },
          alignItems: 'start',
        }}
      >
        <Stack spacing={2} sx={{ minWidth: 0 }}>
          <BookingHeader
            name={serviceName}
            reference={reference}
            status={status}
            emergency={detail?.emergency ?? local?.isEmergency ?? false}
          />
          {showLive ? (
            <LivePanel live={live} destination={detail?.coordinates} />
          ) : status ? (
            <PhasePanel
              bookingId={bookingId}
              status={status}
              detail={detail}
              provider={provider}
              onBookAgain={bookAgain}
              onDetails={goDetails}
              onHelp={() => navigate('/help')}
              onHome={() => navigate('/home')}
            />
          ) : null}
          {provider && status && phase !== 'searching' && phase !== 'searchFailed' ? (
            <ProviderCard provider={provider} />
          ) : null}
        </Stack>

        <Stack spacing={2} sx={{ position: { md: 'sticky' }, top: { md: 96 } }}>
          {status ? (
            <Card>
              <CardContent>
                <Typography variant="h6" component="h2" sx={{ mb: 2 }}>
                  Progress
                </Typography>
                <ProgressTimeline steps={journeySteps(status)} />
              </CardContent>
            </Card>
          ) : null}
          <Card>
            <CardContent>
              <Stack spacing={1.25}>
                {status && CHAT_STATUSES.includes(status) ? (
                  <ChatButton bookingId={bookingId} onOpen={goChat} />
                ) : null}
                <Button variant="outlined" size="large" fullWidth onClick={goDetails}>
                  View booking details
                </Button>
                <Button
                  variant="text"
                  fullWidth
                  startIcon={<SupportAgentRoundedIcon />}
                  onClick={() => navigate('/help')}
                >
                  Get help
                </Button>
              </Stack>
            </CardContent>
          </Card>
        </Stack>
      </Box>
    );
  }

  return (
    <AppShell
      title="Track booking"
      hideBottomNav
      width="full"
      action={showLive ? <ConnectionIndicator connected={live.connected} /> : undefined}
    >
      {body}
    </AppShell>
  );
}

function isNotFound(error: { status: number } | null): boolean {
  return error?.status === 404 || error?.status === 403;
}

/* -------------------------------------------------------------------------- */

function BookingHeader({
  name,
  reference,
  status,
  emergency,
}: {
  name: string | undefined;
  reference: string | undefined;
  status: BookingStatus | undefined;
  emergency: boolean;
}) {
  return (
    <Stack direction="row" spacing={1.5} alignItems="center" justifyContent="space-between">
      <Box sx={{ minWidth: 0 }}>
        <Typography variant="h4" component="h2" noWrap>
          {name ?? 'Your booking'}
        </Typography>
        {reference ? (
          <Typography variant="body2" color="text.secondary">
            Ref {reference}
          </Typography>
        ) : null}
      </Box>
      <Stack direction="row" spacing={1} alignItems="center" sx={{ flexShrink: 0 }}>
        {emergency ? (
          <Stack
            direction="row"
            spacing={0.25}
            alignItems="center"
            sx={{
              px: 1,
              py: 0.25,
              borderRadius: `${radius.pill}px`,
              bgcolor: brand.redSoft,
              color: brand.redDark,
            }}
          >
            <BoltRoundedIcon sx={{ fontSize: 14 }} aria-hidden />
            <Typography variant="caption" fontWeight={700}>
              Emergency
            </Typography>
          </Stack>
        ) : null}
        {status ? <StatusChip status={status} size="medium" /> : null}
      </Stack>
    </Stack>
  );
}

const ripple = keyframes`
  0% { transform: scale(0.6); opacity: 0.55; }
  100% { transform: scale(1.6); opacity: 0; }
`;

/** Pulsing rings around a search icon while dispatch looks for a pro. */
function SearchingVisual() {
  return (
    <Box aria-hidden sx={{ position: 'relative', width: 96, height: 96, mt: 2, mb: 1.5 }}>
      {[0, 0.8, 1.6].map((delay) => (
        <Box
          key={delay}
          sx={{
            position: 'absolute',
            inset: 0,
            borderRadius: '50%',
            border: `2px solid ${brand.accent}`,
            animation: `${ripple} 2.4s ease-out ${delay}s infinite`,
          }}
        />
      ))}
      <Box
        sx={{
          position: 'absolute',
          inset: 18,
          borderRadius: '50%',
          display: 'grid',
          placeItems: 'center',
          bgcolor: 'primary.main',
          color: 'common.white',
          boxShadow: '0 8px 20px -6px rgba(34,81,209,0.6)',
        }}
      >
        <PersonSearchRoundedIcon />
      </Box>
    </Box>
  );
}

function PhasePanel({
  bookingId,
  status,
  detail,
  provider,
  onBookAgain,
  onDetails,
  onHelp,
  onHome,
}: {
  bookingId: string;
  status: BookingStatus;
  detail: BookingDetail | undefined;
  provider: Provider | undefined;
  onBookAgain: () => void;
  onDetails: () => void;
  onHelp: () => void;
  onHome: () => void;
}) {
  const providerName = provider?.displayName ?? 'Your professional';
  const scheduled = detail?.scheduledAt ? formatDateTime(detail.scheduledAt) : null;

  switch (trackingPhase(status)) {
    case 'booked':
      return (
        <StatePanel icon={<HowToRegRoundedIcon />} title="Booking received">
          We&apos;re confirming your booking. We&apos;ll start finding a professional in a moment.
        </StatePanel>
      );
    case 'searching':
      return (
        <StatePanel icon={null} visual={<SearchingVisual />} title="Finding you a professional">
          We&apos;re offering your job to verified pros near you. This usually takes a few minutes —
          we&apos;ll notify you as soon as someone accepts, so you can leave this page.
        </StatePanel>
      );
    case 'searchFailed':
      return (
        <StatePanel
          icon={<SearchOffRoundedIcon />}
          tone="warm"
          title="No professional was available"
          actions={
            <>
              <Button variant="contained" size="large" onClick={onBookAgain}>
                Book again
              </Button>
              <Button variant="outlined" size="large" onClick={onHelp}>
                Contact support
              </Button>
            </>
          }
        >
          We couldn&apos;t find a verified pro nearby for this job right now, and you won&apos;t be
          charged. Try booking again — a different time often helps — or ask our support team for
          help.
        </StatePanel>
      );
    case 'assigned':
      return (
        <StatePanel
          icon={<HowToRegRoundedIcon />}
          tone="success"
          title={
            provider ? `${provider.displayName} is on your job` : 'A professional accepted your job'
          }
        >
          {scheduled ? `Your visit is scheduled for ${scheduled}. ` : ''}
          You&apos;ll see them on the map as soon as they start the trip.
        </StatePanel>
      );
    case 'enRoute':
      return null;
    case 'arrived':
      return (
        <StatePanel
          icon={<DoorFrontRoundedIcon />}
          tone="success"
          title={`${providerName} has arrived`}
        >
          They&apos;re at your address. Work starts once they&apos;ve taken a look at the job.
        </StatePanel>
      );
    case 'working':
      return (
        <StatePanel icon={<HandymanRoundedIcon />} title="Job in progress">
          {providerName} is working on it. We&apos;ll let you know when it&apos;s done.
        </StatePanel>
      );
    case 'paused':
      return (
        <StatePanel icon={<PauseCircleRoundedIcon />} tone="warm" title="Job paused">
          Work is on hold for now. Message your professional if you have any questions.
        </StatePanel>
      );
    case 'approval':
      return <QuoteApprovalPanel bookingId={bookingId} detail={detail} />;
    case 'completed':
    case 'paymentDue':
      // Paying needs the booking's amount; until the booking has been read,
      // say where things stand.
      return detail ? (
        <PaymentPanel detail={detail} />
      ) : (
        <StatePanel
          icon={<PaymentsRoundedIcon />}
          tone="warm"
          title="Job completed"
          actions={
            <Button variant="contained" size="large" onClick={onDetails}>
              View booking
            </Button>
          }
        >
          The job is done and payment is due. Your invoice will be ready once payment is done.
        </StatePanel>
      );
    case 'paid': {
      const paidActions = (
        <>
          <Button variant="contained" size="large" onClick={onDetails}>
            View invoice &amp; details
          </Button>
          <Button variant="outlined" size="large" onClick={onBookAgain}>
            Book again
          </Button>
        </>
      );
      return detail ? (
        <PaymentPanel detail={detail} paidActions={paidActions} />
      ) : (
        <StatePanel
          icon={<TaskAltRoundedIcon />}
          tone="success"
          title="All done — thank you!"
          actions={paidActions}
        >
          Your booking is complete and paid.
        </StatePanel>
      );
    }
    case 'cancelled':
      return (
        <StatePanel
          icon={<EventBusyRoundedIcon />}
          tone="neutral"
          title="This booking was cancelled"
          actions={
            <>
              <Button variant="contained" size="large" onClick={onBookAgain}>
                Book again
              </Button>
              <Button variant="outlined" size="large" onClick={onHome}>
                Back to home
              </Button>
            </>
          }
        >
          No professional will visit for this booking.
        </StatePanel>
      );
    case 'disputed':
      return (
        <StatePanel
          icon={<SupportAgentRoundedIcon />}
          tone="warm"
          title="We're looking into this"
          actions={
            <Button variant="outlined" size="large" onClick={onHelp}>
              Contact support
            </Button>
          }
        >
          Our support team is reviewing this booking and will get back to you.
        </StatePanel>
      );
    case 'refunded':
      return (
        <StatePanel icon={<CurrencyRupeeRoundedIcon />} tone="neutral" title="Refunded">
          This booking has been refunded.
        </StatePanel>
      );
    default:
      return null;
  }
}

/** The live map with ETA, or a calm waiting card until the first location arrives. */
function LivePanel({
  live,
  destination,
}: {
  live: ReturnType<typeof useLiveLocation>;
  /** The service address's position, when the booking carries it. */
  destination: Coordinates | undefined;
}) {
  const now = useNowTick(TRACKING_TICK_MS);
  const snapshot = live.snapshot;
  const target = snapshot?.destination ?? destination;
  // Estimated here: the Location Service does not know the address yet.
  const etaMinutes =
    snapshot && target ? estimateEtaMinutes(snapshot.coordinates, target) : snapshot?.etaMinutes;

  const staleness = useMemo(() => {
    if (!snapshot) return { stale: false, agoLabel: '' };
    const ageMs = now - new Date(snapshot.updatedAt).getTime();
    return {
      stale: Number.isFinite(ageMs) && ageMs > STALE_LOCATION_THRESHOLD_MS,
      agoLabel: formatTimeAgo(snapshot.updatedAt, now),
    };
  }, [snapshot, now]);

  if (!snapshot) {
    return (
      <StatePanel icon={<NearMeRoundedIcon />} title="Your professional is on the way">
        <Stack
          direction="row"
          spacing={1}
          alignItems="center"
          justifyContent={{ xs: 'center', sm: 'flex-start' }}
        >
          {live.isError ? null : <CircularProgress size={14} aria-hidden />}
          <span>
            {live.isError
              ? "We can't show their live location right now, but they're heading to you."
              : 'Their live location will appear here in a moment.'}
          </span>
        </Stack>
      </StatePanel>
    );
  }

  return (
    <Stack spacing={2}>
      {staleness.stale ? (
        <Alert severity="warning" icon={<WarningAmberRoundedIcon />} role="status">
          Location may be out of date — last updated {staleness.agoLabel}.
        </Alert>
      ) : null}
      <Card sx={{ overflow: 'hidden' }}>
        <TrackingMap
          provider={snapshot.coordinates}
          {...(target ? { destination: target } : {})}
          stale={staleness.stale}
        />
        <CardContent sx={{ display: 'flex', alignItems: 'center', gap: 2 }}>
          <IconTile size={48}>
            <AccessTimeRoundedIcon />
          </IconTile>
          <Box sx={{ flexGrow: 1 }}>
            <Typography variant="body2" color="text.secondary">
              {etaMinutes === undefined ? 'Live location' : 'Estimated arrival'}
            </Typography>
            <Typography variant="h3" component="p">
              {etaMinutes === undefined ? 'On the way' : formatEtaMinutes(etaMinutes)}
            </Typography>
          </Box>
          <Typography
            variant="caption"
            color={staleness.stale ? 'warning.dark' : 'text.secondary'}
            sx={{ textAlign: 'right' }}
          >
            Updated
            <br />
            {staleness.agoLabel}
          </Typography>
        </CardContent>
      </Card>
    </Stack>
  );
}

function ConnectionIndicator({ connected }: { connected: boolean }) {
  return (
    <Stack
      direction="row"
      spacing={0.75}
      alignItems="center"
      role="status"
      sx={{
        px: 1.25,
        py: 0.5,
        borderRadius: `${radius.pill}px`,
        bgcolor: connected ? brand.greenSoft : brand.slateSoft,
        color: connected ? '#05603A' : 'text.secondary',
        flexShrink: 0,
      }}
    >
      <Box
        aria-hidden
        sx={{
          width: 8,
          height: 8,
          borderRadius: '50%',
          bgcolor: connected ? 'success.main' : brand.subtle,
        }}
      />
      <Typography variant="caption" fontWeight={700}>
        {connected ? 'Live' : 'Reconnecting…'}
      </Typography>
    </Stack>
  );
}

function ProviderCard({ provider }: { provider: Provider }) {
  const initials = provider.displayName
    .split(' ')
    .map((part) => part.charAt(0))
    .slice(0, 2)
    .join('')
    .toUpperCase();

  return (
    <Card>
      <CardContent>
        <Stack direction="row" spacing={2} alignItems="center">
          <Badge
            overlap="circular"
            anchorOrigin={{ vertical: 'bottom', horizontal: 'right' }}
            badgeContent={
              provider.verified ? (
                <VerifiedRoundedIcon
                  sx={{ fontSize: 18, color: 'success.main', bgcolor: '#fff', borderRadius: '50%' }}
                  aria-hidden
                />
              ) : null
            }
          >
            <Avatar
              aria-hidden
              sx={{ width: 52, height: 52, bgcolor: brand.accentSoft, color: 'primary.main' }}
            >
              {initials}
            </Avatar>
          </Badge>
          <Box sx={{ flexGrow: 1, minWidth: 0 }}>
            <Typography variant="caption" color="text.secondary" display="block">
              Your professional
            </Typography>
            <Typography variant="subtitle1" fontWeight={700} noWrap>
              {provider.displayName}
            </Typography>
            <Stack direction="row" spacing={1} alignItems="center">
              <Stack direction="row" spacing={0.25} alignItems="center">
                <StarRounded sx={{ fontSize: 17, color: brand.warm }} aria-hidden />
                <Typography variant="body2" fontWeight={700}>
                  {provider.rating.toFixed(1)}
                </Typography>
              </Stack>
              {provider.verified ? (
                <Typography variant="body2" color="success.main" fontWeight={600}>
                  Verified
                </Typography>
              ) : null}
            </Stack>
          </Box>
        </Stack>
      </CardContent>
    </Card>
  );
}

/**
 * The professional added parts, so the price changed: the customer approves the
 * new total (work resumes) or declines it (the job completes at the original
 * price). Unanswered, it auto-completes at the original price (Requirement
 * 9.7-9.9).
 */
function QuoteApprovalPanel({
  bookingId,
  detail,
}: {
  bookingId: string;
  detail: BookingDetail | undefined;
}) {
  const decide = useQuoteDecision(bookingId);
  const parts = detail?.parts ?? [];
  const currency = detail?.currency ?? 'INR';
  return (
    <StatePanel
      icon={<RequestQuoteRoundedIcon />}
      tone="warm"
      title="Your approval is needed"
      actions={
        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.5}>
          <Button
            variant="contained"
            size="large"
            disabled={decide.isPending}
            onClick={() => decide.mutate(true)}
          >
            Approve new total
          </Button>
          <Button
            variant="outlined"
            size="large"
            disabled={decide.isPending}
            onClick={() => decide.mutate(false)}
          >
            Decline
          </Button>
        </Stack>
      }
    >
      <Stack spacing={1}>
        <span>
          Your professional needs extra parts. Approve to continue at the new total, or decline to
          finish at the original price.
        </span>
        {parts.map((part) => (
          <span key={part.id}>
            {part.itemName} × {part.quantity} —{' '}
            {formatCurrency(part.quantity * part.unitCost, currency)}
          </span>
        ))}
        {detail?.amount !== undefined ? (
          <strong>New total: {formatCurrency(detail.amount, currency)}</strong>
        ) : null}
        {decide.isError ? (
          <Alert severity="error">{friendlyErrorMessage(decide.error)}</Alert>
        ) : null}
      </Stack>
    </StatePanel>
  );
}
