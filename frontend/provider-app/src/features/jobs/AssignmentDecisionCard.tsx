import { useState } from 'react';
import {
  Alert,
  Button,
  Card,
  CardContent,
  Dialog,
  DialogActions,
  DialogContent,
  DialogContentText,
  DialogTitle,
  Stack,
  Typography,
} from '@mui/material';
import ApartmentRoundedIcon from '@mui/icons-material/ApartmentRounded';
import { isApiError } from '@api/client';
import { useAcceptAssignment, useDeclineAssignment } from './hooks';
import type { JobDetail } from './api';

interface AssignmentDecisionCardProps {
  job: JobDetail;
  /** The provider accepted: continue into the active-job flow. */
  onAccepted: () => void;
  /** The provider declined: the job is no longer theirs, leave the screen. */
  onDeclined: (tenantName: string | null) => void;
  /** The job turned out not to be theirs any more (404): leave the screen. */
  onGone: () => void;
}

/**
 * A job the provider's agency assigned, waiting for the provider's answer
 * (Requirement MT-6.4, MT-13.2): who assigned it, and Accept or Decline.
 * Accepting makes it an ordinary accepted job; declining hands it back to the
 * agency to assign someone else, so it is confirmed first.
 */
export function AssignmentDecisionCard({
  job,
  onAccepted,
  onDeclined,
  onGone,
}: AssignmentDecisionCardProps) {
  const accept = useAcceptAssignment(job.bookingId);
  const decline = useDeclineAssignment(job.bookingId);
  const [confirmingDecline, setConfirmingDecline] = useState(false);

  const agency = job.tenantName ?? 'Your agency';
  const busy = accept.isPending || decline.isPending;
  const failure = accept.error ?? decline.error;
  const gone = isApiError(failure) && failure.status === 404;

  const handleAccept = () => accept.mutate(undefined, { onSuccess: onAccepted });
  const handleDecline = () => {
    setConfirmingDecline(false);
    decline.mutate(undefined, { onSuccess: () => onDeclined(job.tenantName) });
  };

  return (
    <Card variant="outlined" sx={{ borderColor: 'warning.main' }}>
      <CardContent>
        <Stack spacing={1.5}>
          <Stack direction="row" spacing={1} alignItems="center">
            <ApartmentRoundedIcon color="warning" aria-hidden />
            <Typography variant="subtitle1" fontWeight={700}>
              Assigned by {agency}
            </Typography>
          </Stack>
          <Typography variant="body2" color="text.secondary">
            {agency} has assigned this job to you. Accept it to go ahead, or decline so they can
            assign someone else. The customer is waiting for your answer.
          </Typography>

          {failure ? (
            <Alert
              severity={gone ? 'info' : 'error'}
              action={
                gone ? (
                  <Button color="inherit" size="small" onClick={onGone}>
                    Dashboard
                  </Button>
                ) : undefined
              }
            >
              {describeAssignmentError(failure)}
            </Alert>
          ) : null}

          <Stack direction="row" spacing={1.5}>
            <Button
              variant="outlined"
              color="inherit"
              size="large"
              fullWidth
              onClick={() => setConfirmingDecline(true)}
              disabled={busy || gone}
            >
              {decline.isPending ? 'Declining…' : 'Decline'}
            </Button>
            <Button
              variant="contained"
              size="large"
              fullWidth
              onClick={handleAccept}
              disabled={busy || gone}
            >
              {accept.isPending ? 'Accepting…' : 'Accept'}
            </Button>
          </Stack>
        </Stack>
      </CardContent>

      <Dialog
        open={confirmingDecline}
        onClose={() => setConfirmingDecline(false)}
        aria-labelledby="decline-assignment-title"
      >
        <DialogTitle id="decline-assignment-title">Decline this job?</DialogTitle>
        <DialogContent>
          <DialogContentText>
            It goes back to {agency} to assign to someone else, and you won&apos;t see it again.
          </DialogContentText>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setConfirmingDecline(false)}>Keep it</Button>
          <Button color="error" onClick={handleDecline}>
            Decline job
          </Button>
        </DialogActions>
      </Dialog>
    </Card>
  );
}

/** A refused answer, in the provider's terms rather than the API's. */
function describeAssignmentError(error: unknown): string {
  if (!isApiError(error)) return 'Something went wrong. Please try again.';
  if (error.status === 404) return 'This job is no longer assigned to you.';
  if (error.status === 409) {
    return 'This job changed in the meantime — the customer may have cancelled it.';
  }
  return error.message;
}
