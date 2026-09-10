import { useNavigate, useParams } from 'react-router-dom';
import { Alert, Box, Button, Chip, Stack, Typography } from '@mui/material';
import { AppShell } from '@components/AppShell';
import { QueryStateView } from '@components/QueryStateView';
import { isApiError } from '@api/client';
import { PhotoUploadCard } from './PhotoUploadCard';
import { PartsEntryCard } from './PartsEntryCard';
import { PauseResumeCard } from './PauseResumeCard';
import { useCompleteJob, useJobDetail, useStartJob } from './hooks';
import { describeJobStatus } from './status';
import type { JobDetail } from './api';

/**
 * Active Job screen (Requirement 11): before-photo upload (required before
 * JOB_STARTED), pause/resume with a mandatory reason, parts/materials entry,
 * and after-photo upload (required before JOB_COMPLETED). Start and complete
 * actions are guarded client-side by the presence of the relevant photo, with
 * the Booking Service as the authoritative check.
 */
export function ActiveJobScreen() {
  const { bookingId = '' } = useParams();
  const navigate = useNavigate();
  const job = useJobDetail(bookingId);

  return (
    <AppShell title="Active job" onBack={() => navigate(`/jobs/${bookingId}`)}>
      <QueryStateView
        isLoading={job.isLoading}
        isError={job.isError}
        error={job.error}
        onRetry={() => void job.refetch()}
      >
        {job.data ? <ActiveContent job={job.data} /> : null}
      </QueryStateView>
    </AppShell>
  );
}

function ActiveContent({ job }: { job: JobDetail }) {
  const navigate = useNavigate();
  const startJob = useStartJob(job.bookingId);
  const completeJob = useCompleteJob(job.bookingId);

  const beforePhotos = job.photos.filter((photo) => photo.kind === 'BEFORE');
  const afterPhotos = job.photos.filter((photo) => photo.kind === 'AFTER');
  const hasBeforePhoto = beforePhotos.length > 0;
  const hasAfterPhoto = afterPhotos.length > 0;

  const status = describeJobStatus(job.status);

  // The job hasn't started yet: focus on the before-photo + start action.
  const notStarted =
    job.status === 'PROVIDER_ACCEPTED' ||
    job.status === 'PROVIDER_ON_THE_WAY' ||
    job.status === 'PROVIDER_ARRIVED';
  const inProgress = job.status === 'JOB_STARTED' || job.status === 'JOB_PAUSED';
  const isPaused = job.status === 'JOB_PAUSED';

  const handleStart = () => {
    startJob.mutate();
  };
  const handleComplete = () => {
    completeJob.mutate(undefined, {
      onSuccess: () => navigate(`/jobs/${job.bookingId}/complete`),
    });
  };

  return (
    <Stack spacing={2}>
      <Stack direction="row" justifyContent="space-between" alignItems="center">
        <Box>
          <Typography variant="h6" fontWeight={700}>
            {job.serviceName}
          </Typography>
          <Typography variant="caption" color="text.secondary">
            {job.reference}
          </Typography>
        </Box>
        <Chip label={status.label} color={status.color} />
      </Stack>

      <PhotoUploadCard
        bookingId={job.bookingId}
        kind="BEFORE"
        title="Before photo"
        helperText="At least one before-photo is required to start the job."
        photos={beforePhotos}
        disabled={job.status === 'JOB_COMPLETED'}
      />

      {notStarted ? (
        <>
          {!hasBeforePhoto ? (
            <Alert severity="info">Upload a before-photo to enable starting the job.</Alert>
          ) : null}
          {startJob.isError ? (
            <Alert severity="error">
              {isApiError(startJob.error) ? startJob.error.message : 'Could not start the job.'}
            </Alert>
          ) : null}
          <Button
            variant="contained"
            size="large"
            fullWidth
            onClick={handleStart}
            disabled={!hasBeforePhoto || startJob.isPending}
          >
            {startJob.isPending ? 'Starting…' : 'Start job'}
          </Button>
        </>
      ) : null}

      {inProgress ? (
        <>
          <PauseResumeCard bookingId={job.bookingId} status={job.status} />

          <PartsEntryCard
            bookingId={job.bookingId}
            parts={job.parts}
            currency={job.currency}
            disabled={isPaused}
          />

          <PhotoUploadCard
            bookingId={job.bookingId}
            kind="AFTER"
            title="After photo"
            helperText="At least one after-photo is required to complete the job."
            photos={afterPhotos}
          />

          {!hasAfterPhoto ? (
            <Alert severity="info">Upload an after-photo to enable completing the job.</Alert>
          ) : null}
          {completeJob.isError ? (
            <Alert severity="error">
              {isApiError(completeJob.error)
                ? completeJob.error.message
                : 'Could not complete the job.'}
            </Alert>
          ) : null}
          <Button
            variant="contained"
            color="success"
            size="large"
            fullWidth
            onClick={handleComplete}
            disabled={!hasAfterPhoto || isPaused || completeJob.isPending}
          >
            {completeJob.isPending ? 'Completing…' : 'Complete job'}
          </Button>
        </>
      ) : null}

      {job.status === 'JOB_COMPLETED' ? (
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
