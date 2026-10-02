import { useEffect } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import {
  Alert,
  Box,
  Button,
  Card,
  CardContent,
  Chip,
  CircularProgress,
  Stack,
  Typography,
} from '@mui/material';
import BoltRoundedIcon from '@mui/icons-material/BoltRounded';
import InfoOutlinedIcon from '@mui/icons-material/InfoOutlined';
import ScheduleRoundedIcon from '@mui/icons-material/ScheduleRounded';
import { AppShell } from '@components/AppShell';
import { QueryStateView } from '@components/QueryStateView';
import { formatDateTime } from '@lib/format';
import { isApiError } from '@api/client';
import { useCountdown } from '../auth/useCountdown';
import { useAcceptJobOffer, useDeclineJobOffer, useJobOffer } from './hooks';
import { OFFER_ERROR, type JobOffer, type JobOfferStatus } from './api';

/**
 * Job Request screen (Requirement 28.8): shows an incoming offer with
 * accept/decline buttons governed by a live countdown. The offer auto-expires
 * when the countdown hits zero, disabling accept; the server enforces the same
 * deadline, so an accept that races it is refused with OFFER_EXPIRED.
 *
 * The Dispatch Engine knows only what matching needs (reference, emergency
 * flag, slot), so the address, description and earning are not shown here;
 * they arrive with the job itself once the offer is accepted.
 */
export function JobRequestScreen() {
  const { bookingId = '' } = useParams();
  const navigate = useNavigate();
  const offer = useJobOffer(bookingId);

  return (
    <AppShell title="Job request" onBack={() => navigate('/dashboard')}>
      <QueryStateView
        isLoading={offer.isLoading}
        isError={offer.isError}
        error={offer.error}
        onRetry={() => void offer.refetch()}
      >
        {offer.data ? (
          <OfferContent
            offer={offer.data}
            bookingId={bookingId}
            onDone={() => navigate('/dashboard')}
          />
        ) : null}
      </QueryStateView>
    </AppShell>
  );
}

interface OfferContentProps {
  offer: JobOffer;
  bookingId: string;
  /** Leave the screen once the provider has answered. */
  onDone: () => void;
}

function OfferContent({ offer, bookingId, onDone }: OfferContentProps) {
  const timer = useCountdown(0);
  const timerReset = timer.reset;
  useEffect(() => {
    timerReset(Math.max(0, Math.floor(offer.expiresInSeconds)));
  }, [offer.expiresInSeconds, timerReset]);

  const accept = useAcceptJobOffer(bookingId);
  const decline = useDeclineJobOffer(bookingId);

  const isPending = offer.status === 'PENDING';
  const isExpired = !isPending || timer.secondsLeft <= 0;
  const isBusy = accept.isPending || decline.isPending;
  // Fall back to the offer's own remaining time if the window length is missing.
  const windowSeconds = offer.timeoutSeconds > 0 ? offer.timeoutSeconds : offer.expiresInSeconds;
  const ringValue =
    windowSeconds > 0 ? Math.min(100, (timer.secondsLeft / windowSeconds) * 100) : 0;

  const handleAccept = () => {
    accept.mutate(undefined, { onSuccess: onDone });
  };
  const handleDecline = () => {
    decline.mutate(undefined, { onSuccess: onDone });
  };

  const decisionError = accept.error ?? decline.error;
  const errorMessage =
    (accept.isError || decline.isError) && isApiError(decisionError)
      ? describeDecisionError(decisionError.code, decisionError.message)
      : null;

  return (
    <Stack spacing={2}>
      <Box sx={{ display: 'flex', flexDirection: 'column', alignItems: 'center', py: 1 }}>
        <Box sx={{ position: 'relative', display: 'inline-flex' }}>
          <CircularProgress
            variant="determinate"
            value={ringValue}
            size={96}
            thickness={4}
            color={isExpired ? 'error' : timer.secondsLeft <= 10 ? 'warning' : 'primary'}
            aria-label="Time remaining to respond"
          />
          <Box
            sx={{
              position: 'absolute',
              inset: 0,
              display: 'flex',
              flexDirection: 'column',
              alignItems: 'center',
              justifyContent: 'center',
            }}
          >
            <Typography variant="h5" fontWeight={700} aria-live="polite">
              {timer.secondsLeft}
            </Typography>
            <Typography variant="caption" color="text.secondary">
              sec
            </Typography>
          </Box>
        </Box>
        <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }} aria-live="polite">
          {isPending
            ? isExpired
              ? 'This offer has expired'
              : 'Respond before the timer runs out'
            : describeClosedOffer(offer.status)}
        </Typography>
      </Box>

      <Card variant="outlined">
        <CardContent>
          <Stack direction="row" justifyContent="space-between" alignItems="flex-start" spacing={1}>
            <Stack spacing={0.25}>
              <Typography variant="h6" fontWeight={700}>
                {offer.emergency ? 'Emergency job' : 'New job'}
              </Typography>
              {offer.reference ? (
                <Typography variant="caption" color="text.secondary">
                  {offer.reference}
                </Typography>
              ) : null}
            </Stack>
            {offer.emergency ? (
              <Chip
                size="small"
                color="error"
                variant="outlined"
                icon={<BoltRoundedIcon />}
                label="Emergency"
              />
            ) : null}
          </Stack>

          <Stack spacing={0.75} sx={{ mt: 1.5 }}>
            <Stack direction="row" spacing={1} alignItems="center" color="text.secondary">
              <ScheduleRoundedIcon fontSize="small" aria-hidden />
              <Typography variant="body2">
                {offer.scheduledAt ? formatDateTime(offer.scheduledAt) : 'As soon as possible'}
              </Typography>
            </Stack>
            <Stack direction="row" spacing={1} alignItems="flex-start" color="text.secondary">
              <InfoOutlinedIcon fontSize="small" aria-hidden sx={{ mt: 0.25 }} />
              <Typography variant="body2">
                The address, job description and earning are shared once you accept.
              </Typography>
            </Stack>
          </Stack>
        </CardContent>
      </Card>

      {errorMessage ? <Alert severity="error">{errorMessage}</Alert> : null}

      <Stack direction="row" spacing={1.5}>
        <Button
          variant="outlined"
          color="inherit"
          size="large"
          fullWidth
          onClick={handleDecline}
          disabled={isBusy || !isPending}
        >
          {decline.isPending ? 'Declining…' : 'Decline'}
        </Button>
        <Button
          variant="contained"
          size="large"
          fullWidth
          onClick={handleAccept}
          disabled={isBusy || isExpired}
        >
          {accept.isPending ? 'Accepting…' : 'Accept'}
        </Button>
      </Stack>
    </Stack>
  );
}

/** Status line for an offer that can no longer be answered. */
function describeClosedOffer(status: JobOfferStatus): string {
  switch (status) {
    case 'ACCEPTED':
      return 'You accepted this job';
    case 'DECLINED':
      return 'You declined this job';
    case 'WITHDRAWN':
      return 'The customer cancelled this booking';
    case 'EXPIRED':
    default:
      return 'This offer has expired';
  }
}

/** A refused decision, in the provider's terms rather than the API's. */
function describeDecisionError(code: string, fallback: string): string {
  switch (code) {
    case OFFER_ERROR.expired:
      return 'Too late: this offer expired before your answer arrived.';
    case OFFER_ERROR.alreadyDecided:
      return 'This offer has already been answered or withdrawn.';
    case OFFER_ERROR.notFound:
      return 'This offer is no longer available.';
    default:
      return fallback;
  }
}
