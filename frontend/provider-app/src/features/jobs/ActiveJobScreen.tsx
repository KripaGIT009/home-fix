import { useNavigate, useParams } from 'react-router-dom';
import { Alert, Box, Button, Chip, Stack, Typography } from '@mui/material';
import { AppShell } from '@components/AppShell';
import { QueryStateView } from '@components/QueryStateView';
import { isApiError } from '@api/client';
import { describeBookingStatus } from '@lib/bookingStatus';
import { PhotoUploadCard } from './PhotoUploadCard';
import { PartsEntryCard } from './PartsEntryCard';
import { PauseResumeCard } from './PauseResumeCard';
import { PaymentStatusNotice } from './PaymentStatusNotice';
import { isJobFinished } from './status';
import {
  useCompleteJob,
  useJobDetail,
  useMarkArrived,
  useMarkOnTheWay,
  useShareLocation,
  useStartJob,
  type LocationShareState,
} from './hooks';
import { buildNavigationDeepLink } from './navigation';
import type { JobDetail } from './api';

/**
 * Active Job screen (Requirements 9.3-9.10, 11): set off and arrive (the job
 * can only be started once the provider has arrived), before-photo upload
 * (required before JOB_STARTED), pause/resume with a mandatory reason,
 * parts/materials entry, and after-photo upload (required before
 * JOB_COMPLETED). Start and complete actions are guarded client-side by the
 * presence of the relevant photo, with the Booking Service as the
 * authoritative check.
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
  const onTheWay = useMarkOnTheWay(job.bookingId);
  const arrived = useMarkArrived(job.bookingId);
  const startJob = useStartJob(job.bookingId);
  const completeJob = useCompleteJob(job.bookingId);

  const beforePhotos = job.photos.filter((photo) => photo.kind === 'BEFORE');
  const afterPhotos = job.photos.filter((photo) => photo.kind === 'AFTER');
  const hasBeforePhoto = beforePhotos.length > 0;
  const hasAfterPhoto = afterPhotos.length > 0;

  const status = describeBookingStatus(job.status);

  // Before the job: set off, arrive, then photograph and start. The Booking
  // Service only allows JOB_STARTED from PROVIDER_ARRIVED.
  const notSetOff = job.status === 'PROVIDER_ASSIGNED' || job.status === 'PROVIDER_ACCEPTED';
  const enRoute = job.status === 'PROVIDER_ON_THE_WAY';
  const arrivedOnSite = job.status === 'PROVIDER_ARRIVED';
  const inProgress = job.status === 'JOB_STARTED' || job.status === 'JOB_PAUSED';
  const isPaused = job.status === 'JOB_PAUSED';
  const sharing = useShareLocation(job.bookingId, enRoute);
  const awaitingApproval =
    job.status === 'ADDITIONAL_QUOTE_REQUIRED' || job.status === 'CUSTOMER_APPROVAL_PENDING';

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
        disabled={!arrivedOnSite}
      />

      {job.serviceAddress || job.coordinates ? (
        <Box>
          {job.serviceAddress ? (
            <Typography variant="body2">{job.serviceAddress}</Typography>
          ) : null}
          {job.coordinates && (notSetOff || enRoute) ? (
            <Button
              size="small"
              href={buildNavigationDeepLink(job.coordinates.latitude, job.coordinates.longitude)}
              target="_blank"
              rel="noopener noreferrer"
            >
              Navigate
            </Button>
          ) : null}
        </Box>
      ) : null}

      {notSetOff ? (
        <TransitionButton label="I'm on my way" pendingLabel="Updating…" mutation={onTheWay} />
      ) : null}
      {enRoute ? (
        <>
          <LocationShareNotice state={sharing} />
          <TransitionButton label="I've arrived" pendingLabel="Updating…" mutation={arrived} />
        </>
      ) : null}

      {arrivedOnSite ? (
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

      {awaitingApproval ? (
        <Alert severity="info">
          Waiting for the customer to approve the updated quote. If they don&apos;t answer, the job
          completes at the original price.
        </Alert>
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

      {isJobFinished(job.status) ? (
        <>
          <PaymentStatusNotice status={job.status} />
          <Button
            variant="contained"
            size="large"
            fullWidth
            onClick={() => navigate(`/jobs/${job.bookingId}/complete`)}
          >
            View completion summary
          </Button>
        </>
      ) : null}
    </Stack>
  );
}

/** A full-width button that runs one status transition and shows its error. */
function TransitionButton({
  label,
  pendingLabel,
  mutation,
}: {
  label: string;
  pendingLabel: string;
  mutation: { mutate: () => void; isPending: boolean; isError: boolean; error: unknown };
}) {
  return (
    <>
      {mutation.isError ? (
        <Alert severity="error">
          {isApiError(mutation.error) ? mutation.error.message : 'Could not update the job.'}
        </Alert>
      ) : null}
      <Button
        variant="contained"
        size="large"
        fullWidth
        onClick={() => mutation.mutate()}
        disabled={mutation.isPending}
      >
        {mutation.isPending ? pendingLabel : label}
      </Button>
    </>
  );
}

/** Tells the provider whether the customer can see them on the map. */
function LocationShareNotice({ state }: { state: LocationShareState }) {
  if (state === 'sharing') {
    return <Alert severity="success">Sharing your location with the customer.</Alert>;
  }
  if (state === 'denied') {
    return (
      <Alert severity="warning">
        Location permission is off, so the customer can&apos;t see you on the map. Allow location
        access for HomeFix to share it.
      </Alert>
    );
  }
  if (state === 'unavailable') {
    return (
      <Alert severity="warning">
        Your location isn&apos;t available right now, so the customer can&apos;t see you on the map.
      </Alert>
    );
  }
  return null;
}
