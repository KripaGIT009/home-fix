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
import PlaceRoundedIcon from '@mui/icons-material/PlaceRounded';
import ScheduleRoundedIcon from '@mui/icons-material/ScheduleRounded';
import { AppShell } from '@components/AppShell';
import { QueryStateView } from '@components/QueryStateView';
import { formatCurrency, formatDateTime } from '@lib/format';
import { isApiError } from '@api/client';
import { useCountdown } from '../auth/useCountdown';
import { useAcceptJob, useDeclineJob, useJobOffer } from './hooks';
import type { JobOffer } from './api';

/**
 * Job Request screen (Requirement 28.8): shows the incoming offer's service
 * details and customer info with accept/decline buttons governed by a live
 * countdown. The offer auto-expires when the countdown hits zero, disabling
 * accept.
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
            onAccepted={() => navigate(`/jobs/${bookingId}`)}
          />
        ) : null}
      </QueryStateView>
    </AppShell>
  );
}

interface OfferContentProps {
  offer: JobOffer;
  bookingId: string;
  onDone: () => void;
  onAccepted: () => void;
}

function OfferContent({ offer, bookingId, onDone, onAccepted }: OfferContentProps) {
  const timer = useCountdown(0);
  const timerReset = timer.reset;
  useEffect(() => {
    timerReset(Math.max(0, Math.floor(offer.expiresInSeconds)));
  }, [offer.expiresInSeconds, timerReset]);

  const accept = useAcceptJob(bookingId);
  const decline = useDeclineJob(bookingId);

  const isExpired = timer.secondsLeft <= 0;
  const isBusy = accept.isPending || decline.isPending;

  const handleAccept = () => {
    accept.mutate(undefined, { onSuccess: onAccepted });
  };
  const handleDecline = () => {
    decline.mutate(undefined, { onSuccess: onDone });
  };

  const errorMessage =
    accept.isError && isApiError(accept.error)
      ? accept.error.message
      : decline.isError && isApiError(decline.error)
        ? decline.error.message
        : null;

  return (
    <Stack spacing={2}>
      <Box sx={{ display: 'flex', flexDirection: 'column', alignItems: 'center', py: 1 }}>
        <Box sx={{ position: 'relative', display: 'inline-flex' }}>
          <CircularProgress
            variant="determinate"
            value={(timer.secondsLeft / 60) * 100}
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
          {isExpired ? 'This offer has expired' : 'Respond before the timer runs out'}
        </Typography>
      </Box>

      <Card variant="outlined">
        <CardContent>
          <Stack direction="row" justifyContent="space-between" alignItems="flex-start" spacing={1}>
            <Stack spacing={0.25}>
              <Typography variant="h6" fontWeight={700}>
                {offer.serviceName}
              </Typography>
              <Typography variant="caption" color="text.secondary">
                {offer.reference}
              </Typography>
            </Stack>
            {offer.isEmergency ? (
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
              <Typography variant="body2">{formatDateTime(offer.scheduledAt)}</Typography>
            </Stack>
            <Stack direction="row" spacing={1} alignItems="center" color="text.secondary">
              <PlaceRoundedIcon fontSize="small" aria-hidden />
              <Typography variant="body2">{offer.customerArea}</Typography>
            </Stack>
          </Stack>

          {offer.description ? (
            <Typography variant="body2" sx={{ mt: 1.5 }}>
              {offer.description}
            </Typography>
          ) : null}

          {offer.estimatedEarning !== null ? (
            <Stack direction="row" justifyContent="space-between" sx={{ mt: 2 }}>
              <Typography variant="body2" color="text.secondary">
                Estimated earning
              </Typography>
              <Typography variant="subtitle1" fontWeight={700}>
                {formatCurrency(offer.estimatedEarning, offer.currency)}
              </Typography>
            </Stack>
          ) : null}
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
          disabled={isBusy}
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
