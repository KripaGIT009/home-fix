import { Card, CardActionArea, CardContent, Chip, Stack, Typography } from '@mui/material';
import PlaceRoundedIcon from '@mui/icons-material/PlaceRounded';
import ScheduleRoundedIcon from '@mui/icons-material/ScheduleRounded';
import BoltRoundedIcon from '@mui/icons-material/BoltRounded';
import { describeBookingStatus } from '@lib/bookingStatus';
import { formatCurrency, formatDateTime } from '@lib/format';
import type { ActiveJob } from './api';

interface ActiveJobCardProps {
  job: ActiveJob;
  onOpen: (bookingId: string) => void;
}

/**
 * A single active job card with a coloured status indicator (Requirement
 * 28.8). Emergency jobs are flagged; the card is a large touch target that
 * opens the job details.
 */
export function ActiveJobCard({ job, onOpen }: ActiveJobCardProps) {
  const status = describeBookingStatus(job.status);

  return (
    <Card variant="outlined">
      <CardActionArea onClick={() => onOpen(job.bookingId)}>
        <CardContent>
          <Stack direction="row" justifyContent="space-between" alignItems="flex-start" spacing={1}>
            <Stack spacing={0.25}>
              <Typography variant="subtitle1" fontWeight={700}>
                {job.serviceName}
              </Typography>
              <Typography variant="caption" color="text.secondary">
                {job.reference}
              </Typography>
            </Stack>
            <Chip size="small" label={status.label} color={status.color} />
          </Stack>

          <Stack spacing={0.75} sx={{ mt: 1.5 }}>
            <Stack direction="row" spacing={1} alignItems="center" color="text.secondary">
              <ScheduleRoundedIcon fontSize="small" aria-hidden />
              <Typography variant="body2">{formatDateTime(job.scheduledAt)}</Typography>
            </Stack>
            <Stack direction="row" spacing={1} alignItems="center" color="text.secondary">
              <PlaceRoundedIcon fontSize="small" aria-hidden />
              <Typography variant="body2">{job.customerArea}</Typography>
            </Stack>
          </Stack>

          <Stack direction="row" spacing={1} alignItems="center" sx={{ mt: 1.5 }}>
            {job.isEmergency ? (
              <Chip
                size="small"
                color="error"
                variant="outlined"
                icon={<BoltRoundedIcon />}
                label="Emergency"
              />
            ) : null}
            {job.estimatedEarning !== null ? (
              <Typography variant="body2" fontWeight={700} sx={{ ml: 'auto' }}>
                {formatCurrency(job.estimatedEarning)}
              </Typography>
            ) : null}
          </Stack>
        </CardContent>
      </CardActionArea>
    </Card>
  );
}
