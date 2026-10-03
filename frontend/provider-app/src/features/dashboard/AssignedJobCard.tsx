import { Card, CardActionArea, CardContent, Chip, Stack, Typography } from '@mui/material';
import ApartmentRoundedIcon from '@mui/icons-material/ApartmentRounded';
import BoltRoundedIcon from '@mui/icons-material/BoltRounded';
import ScheduleRoundedIcon from '@mui/icons-material/ScheduleRounded';
import { formatCurrency, formatDateTime } from '@lib/format';
import { useJobDetail } from '@features/jobs/hooks';
import type { ActiveJob } from './api';

interface AssignedJobCardProps {
  job: ActiveJob;
  onOpen: (bookingId: string) => void;
}

/**
 * A job the provider's agency assigned and the provider has yet to answer
 * (Requirement MT-6.4, MT-13.2). The active-job list does not carry the
 * agency's name, so it is read from the job detail — which the job screen
 * needs next anyway, and which polls while the job waits for an answer.
 */
export function AssignedJobCard({ job, onOpen }: AssignedJobCardProps) {
  const detail = useJobDetail(job.bookingId);
  const agency = detail.data?.tenantName ?? 'your agency';

  return (
    <Card variant="outlined" sx={{ borderColor: 'warning.main' }}>
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
            <Chip size="small" label="Accept or decline" color="warning" />
          </Stack>

          <Stack spacing={0.75} sx={{ mt: 1.5 }}>
            <Stack direction="row" spacing={1} alignItems="center">
              <ApartmentRoundedIcon fontSize="small" color="warning" aria-hidden />
              <Typography variant="body2" fontWeight={600}>
                Assigned by {agency}
              </Typography>
            </Stack>
            <Stack direction="row" spacing={1} alignItems="center" color="text.secondary">
              <ScheduleRoundedIcon fontSize="small" aria-hidden />
              <Typography variant="body2">{formatDateTime(job.scheduledAt)}</Typography>
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
