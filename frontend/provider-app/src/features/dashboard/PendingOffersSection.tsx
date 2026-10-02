import { useEffect, useState } from 'react';
import {
  Card,
  CardActionArea,
  CardContent,
  Chip,
  LinearProgress,
  Stack,
  Typography,
} from '@mui/material';
import BoltRoundedIcon from '@mui/icons-material/BoltRounded';
import ScheduleRoundedIcon from '@mui/icons-material/ScheduleRounded';
import { formatDateTime } from '@lib/format';
import { usePendingJobOffers } from '@features/jobs/hooks';
import type { JobOffer } from '@features/jobs/api';

interface PendingOffersSectionProps {
  onOpen: (bookingId: string) => void;
}

/**
 * Open job offers at the top of the Dashboard (Requirement 28.8), polled every
 * 5 s so an offer shows up even when its push notification does not.
 *
 * The section renders nothing while there are no offers, and also when a poll
 * fails: the next poll retries on its own, and an error banner flashing every
 * few seconds would bury the rest of the dashboard.
 */
export function PendingOffersSection({ onOpen }: PendingOffersSectionProps) {
  const offers = usePendingJobOffers();
  const now = useSecondTick();

  // Seconds elapsed since the server computed each offer's expiresInSeconds.
  const elapsed = offers.dataUpdatedAt > 0 ? Math.max(0, (now - offers.dataUpdatedAt) / 1000) : 0;
  const open = (offers.data ?? [])
    .map((offer) => ({ offer, secondsLeft: Math.floor(offer.expiresInSeconds - elapsed) }))
    .filter(({ secondsLeft }) => secondsLeft > 0);

  if (open.length === 0) {
    return null;
  }

  return (
    <Stack spacing={1.5}>
      <Stack direction="row" justifyContent="space-between" alignItems="baseline">
        <Typography variant="subtitle1" fontWeight={700}>
          Job offers
        </Typography>
        <Typography variant="caption" color="text.secondary">
          {open.length} waiting for you
        </Typography>
      </Stack>
      {open.map(({ offer, secondsLeft }) => (
        <PendingOfferCard
          key={offer.bookingId}
          offer={offer}
          secondsLeft={secondsLeft}
          onOpen={onOpen}
        />
      ))}
    </Stack>
  );
}

interface PendingOfferCardProps {
  offer: JobOffer;
  secondsLeft: number;
  onOpen: (bookingId: string) => void;
}

function PendingOfferCard({ offer, secondsLeft, onOpen }: PendingOfferCardProps) {
  const windowSeconds = offer.timeoutSeconds > 0 ? offer.timeoutSeconds : offer.expiresInSeconds;
  const progress = windowSeconds > 0 ? Math.min(100, (secondsLeft / windowSeconds) * 100) : 0;

  return (
    <Card variant="outlined" sx={{ borderColor: offer.emergency ? 'error.main' : 'primary.main' }}>
      <CardActionArea onClick={() => onOpen(offer.bookingId)}>
        <CardContent>
          <Stack direction="row" justifyContent="space-between" alignItems="flex-start" spacing={1}>
            <Stack spacing={0.25}>
              <Typography variant="subtitle1" fontWeight={700}>
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

          <Stack
            direction="row"
            spacing={1}
            alignItems="center"
            color="text.secondary"
            sx={{ mt: 1.5 }}
          >
            <ScheduleRoundedIcon fontSize="small" aria-hidden />
            <Typography variant="body2">
              {offer.scheduledAt ? formatDateTime(offer.scheduledAt) : 'As soon as possible'}
            </Typography>
          </Stack>

          <Stack spacing={0.5} sx={{ mt: 1.5 }}>
            <LinearProgress
              variant="determinate"
              value={progress}
              color={secondsLeft <= 10 ? 'warning' : 'primary'}
              aria-label="Time remaining to respond"
            />
            <Typography variant="caption" color="text.secondary">
              Respond within {secondsLeft} s
            </Typography>
          </Stack>
        </CardContent>
      </CardActionArea>
    </Card>
  );
}

/** The current time, re-read once a second so countdowns keep moving between polls. */
function useSecondTick(): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(id);
  }, []);
  return now;
}
