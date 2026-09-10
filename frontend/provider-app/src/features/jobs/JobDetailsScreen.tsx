import { useNavigate, useParams } from 'react-router-dom';
import {
  Box,
  Button,
  Card,
  CardContent,
  Chip,
  Divider,
  ImageList,
  ImageListItem,
  Link,
  Stack,
  Typography,
} from '@mui/material';
import BoltRoundedIcon from '@mui/icons-material/BoltRounded';
import DirectionsRoundedIcon from '@mui/icons-material/DirectionsRounded';
import PersonRoundedIcon from '@mui/icons-material/PersonRounded';
import PlaceRoundedIcon from '@mui/icons-material/PlaceRounded';
import ScheduleRoundedIcon from '@mui/icons-material/ScheduleRounded';
import { AppShell } from '@components/AppShell';
import { QueryStateView } from '@components/QueryStateView';
import { formatCurrency, formatDateTime } from '@lib/format';
import { useJobDetail } from './hooks';
import { describeJobStatus } from './status';
import { buildNavigationDeepLink } from './navigation';
import type { JobDetail } from './api';

/**
 * Job Details screen (Requirement 11.1): customer display name, service
 * address, description, customer-uploaded media, and a navigation deep-link to
 * the customer's coordinates. Also routes the Provider onward to the active-job
 * or completion flow depending on the booking state.
 */
export function JobDetailsScreen() {
  const { bookingId = '' } = useParams();
  const navigate = useNavigate();
  const job = useJobDetail(bookingId);

  return (
    <AppShell title="Job details" onBack={() => navigate('/dashboard')}>
      <QueryStateView
        isLoading={job.isLoading}
        isError={job.isError}
        error={job.error}
        onRetry={() => void job.refetch()}
      >
        {job.data ? <DetailContent job={job.data} /> : null}
      </QueryStateView>
    </AppShell>
  );
}

function DetailContent({ job }: { job: JobDetail }) {
  const navigate = useNavigate();
  const status = describeJobStatus(job.status);
  const navLink = buildNavigationDeepLink(job.coordinates.latitude, job.coordinates.longitude);

  const showActiveCta =
    job.status === 'PROVIDER_ACCEPTED' ||
    job.status === 'PROVIDER_ON_THE_WAY' ||
    job.status === 'PROVIDER_ARRIVED' ||
    job.status === 'JOB_STARTED' ||
    job.status === 'JOB_PAUSED';
  const showCompleteCta = job.status === 'JOB_COMPLETED';

  return (
    <Stack spacing={2}>
      <Card variant="outlined">
        <CardContent>
          <Stack direction="row" justifyContent="space-between" alignItems="flex-start" spacing={1}>
            <Stack spacing={0.25}>
              <Typography variant="h6" fontWeight={700}>
                {job.serviceName}
              </Typography>
              <Typography variant="caption" color="text.secondary">
                {job.reference}
              </Typography>
            </Stack>
            <Stack direction="row" spacing={0.5} alignItems="center">
              {job.isEmergency ? (
                <Chip
                  size="small"
                  color="error"
                  variant="outlined"
                  icon={<BoltRoundedIcon />}
                  label="Emergency"
                />
              ) : null}
              <Chip size="small" label={status.label} color={status.color} />
            </Stack>
          </Stack>

          <Stack spacing={1} sx={{ mt: 2 }}>
            <Stack direction="row" spacing={1} alignItems="center">
              <PersonRoundedIcon fontSize="small" color="action" aria-hidden />
              <Typography variant="body2">{job.customerDisplayName}</Typography>
            </Stack>
            <Stack direction="row" spacing={1} alignItems="flex-start">
              <PlaceRoundedIcon fontSize="small" color="action" aria-hidden />
              <Typography variant="body2">{job.serviceAddress}</Typography>
            </Stack>
            <Stack direction="row" spacing={1} alignItems="center">
              <ScheduleRoundedIcon fontSize="small" color="action" aria-hidden />
              <Typography variant="body2">{formatDateTime(job.scheduledAt)}</Typography>
            </Stack>
          </Stack>

          <Button
            component={Link}
            href={navLink}
            target="_blank"
            rel="noopener"
            variant="outlined"
            startIcon={<DirectionsRoundedIcon />}
            sx={{ mt: 2 }}
            fullWidth
          >
            Navigate to location
          </Button>
        </CardContent>
      </Card>

      <Card variant="outlined">
        <CardContent>
          <Typography variant="subtitle2" fontWeight={700} sx={{ mb: 1 }}>
            Service description
          </Typography>
          <Typography variant="body2" color={job.description ? 'text.primary' : 'text.secondary'}>
            {job.description || 'No description provided.'}
          </Typography>

          {job.estimatedEarning !== null ? (
            <>
              <Divider sx={{ my: 1.5 }} />
              <Stack direction="row" justifyContent="space-between">
                <Typography variant="body2" color="text.secondary">
                  Estimated earning
                </Typography>
                <Typography variant="subtitle1" fontWeight={700}>
                  {formatCurrency(job.estimatedEarning, job.currency)}
                </Typography>
              </Stack>
            </>
          ) : null}
        </CardContent>
      </Card>

      <Card variant="outlined">
        <CardContent>
          <Typography variant="subtitle2" fontWeight={700} sx={{ mb: 1 }}>
            Customer photos
          </Typography>
          {job.customerMedia.length > 0 ? (
            <ImageList cols={3} gap={8} sx={{ m: 0 }}>
              {job.customerMedia.map((media) => (
                <ImageListItem key={media.id}>
                  <Box
                    component="img"
                    src={media.url}
                    alt={media.label ?? 'Customer-uploaded photo'}
                    loading="lazy"
                    sx={{ borderRadius: 1, aspectRatio: '1 / 1', objectFit: 'cover' }}
                  />
                </ImageListItem>
              ))}
            </ImageList>
          ) : (
            <Typography variant="body2" color="text.secondary">
              No customer photos attached.
            </Typography>
          )}
        </CardContent>
      </Card>

      {showActiveCta ? (
        <Button
          variant="contained"
          size="large"
          fullWidth
          onClick={() => navigate(`/jobs/${job.bookingId}/active`)}
        >
          Open active job
        </Button>
      ) : null}
      {showCompleteCta ? (
        <Button
          variant="contained"
          size="large"
          fullWidth
          onClick={() => navigate(`/jobs/${job.bookingId}/complete`)}
        >
          View completion summary
        </Button>
      ) : null}
    </Stack>
  );
}
