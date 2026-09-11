import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { Alert, Button, Card, CardContent, Stack, TextField, Typography } from '@mui/material';
import PauseRoundedIcon from '@mui/icons-material/PauseRounded';
import PlayArrowRoundedIcon from '@mui/icons-material/PlayArrowRounded';
import { isApiError } from '@api/client';
import type { BookingStatus } from '@lib/bookingStatus';
import { usePauseJob, useResumeJob } from './hooks';
import { pauseReasonSchema, type PauseReasonFormValues } from './schemas';

interface PauseResumeCardProps {
  bookingId: string;
  status: BookingStatus;
}

/**
 * Pause/resume control (Requirement 11.5): pausing requires a mandatory reason
 * (1–500 characters); resuming returns the job to JOB_STARTED. The reason form
 * only appears once the Provider chooses to pause a running job.
 */
export function PauseResumeCard({ bookingId, status }: PauseResumeCardProps) {
  const [showReason, setShowReason] = useState(false);
  const pause = usePauseJob(bookingId);
  const resume = useResumeJob(bookingId);

  const {
    register,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<PauseReasonFormValues>({
    resolver: zodResolver(pauseReasonSchema),
    defaultValues: { reason: '' },
    mode: 'onSubmit',
  });

  const isPaused = status === 'JOB_PAUSED';

  const onPause = (values: PauseReasonFormValues) => {
    pause.mutate(values.reason, {
      onSuccess: () => {
        setShowReason(false);
        reset({ reason: '' });
      },
    });
  };

  if (isPaused) {
    return (
      <Card variant="outlined">
        <CardContent>
          <Typography variant="subtitle2" fontWeight={700}>
            Job paused
          </Typography>
          <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5 }}>
            Resume when you&apos;re ready to continue working.
          </Typography>
          {resume.isError ? (
            <Alert severity="error" sx={{ mt: 1.5 }}>
              {isApiError(resume.error) ? resume.error.message : 'Could not resume the job.'}
            </Alert>
          ) : null}
          <Button
            variant="contained"
            startIcon={<PlayArrowRoundedIcon />}
            onClick={() => resume.mutate()}
            disabled={resume.isPending}
            sx={{ mt: 1.5 }}
            fullWidth
          >
            {resume.isPending ? 'Resuming…' : 'Resume job'}
          </Button>
        </CardContent>
      </Card>
    );
  }

  return (
    <Card variant="outlined">
      <CardContent>
        <Typography variant="subtitle2" fontWeight={700}>
          Need a break?
        </Typography>
        {showReason ? (
          <Stack
            component="form"
            spacing={1.5}
            sx={{ mt: 1 }}
            onSubmit={(event) => void handleSubmit(onPause)(event)}
            noValidate
          >
            <TextField
              label="Reason for pausing"
              multiline
              minRows={2}
              fullWidth
              size="small"
              error={Boolean(errors.reason)}
              helperText={errors.reason?.message ?? 'Required, up to 500 characters'}
              {...register('reason')}
            />
            {pause.isError ? (
              <Alert severity="error">
                {isApiError(pause.error) ? pause.error.message : 'Could not pause the job.'}
              </Alert>
            ) : null}
            <Stack direction="row" spacing={1.5}>
              <Button
                variant="text"
                color="inherit"
                fullWidth
                onClick={() => {
                  setShowReason(false);
                  reset({ reason: '' });
                }}
                disabled={pause.isPending}
              >
                Cancel
              </Button>
              <Button type="submit" variant="contained" fullWidth disabled={pause.isPending}>
                {pause.isPending ? 'Pausing…' : 'Confirm pause'}
              </Button>
            </Stack>
          </Stack>
        ) : (
          <Button
            variant="outlined"
            startIcon={<PauseRoundedIcon />}
            onClick={() => setShowReason(true)}
            sx={{ mt: 1 }}
            fullWidth
          >
            Pause job
          </Button>
        )}
      </CardContent>
    </Card>
  );
}
