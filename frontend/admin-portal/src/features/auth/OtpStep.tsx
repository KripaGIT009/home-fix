import { useEffect } from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { Alert, Box, Button, Stack, TextField, Typography } from '@mui/material';
import LockClockRoundedIcon from '@mui/icons-material/LockClockRounded';
import { OTP_LENGTH } from './constants';
import { formatDuration, type LockoutInfo } from './lockout';
import { useCountdown } from './useCountdown';
import { otpSchema, type OtpFormValues } from './schemas';

interface OtpStepProps {
  onVerify: (otp: string) => void;
  onResend: () => void;
  onChangeNumber: () => void;
  isVerifying: boolean;
  /** Remaining OTP validity in seconds (Requirement 1.4). */
  secondsLeft: number;
  /** Lockout details when the session is locked (Requirement 1.3). */
  lockout: LockoutInfo | null;
}

/**
 * Step 2 of the login flow: enter the 6-digit OTP within the expiry window,
 * with a live countdown, resend once expired, and a lockout notice with the
 * remaining lockout time (Requirement 1.2–1.4).
 */
export function OtpStep({
  onVerify,
  onResend,
  onChangeNumber,
  isVerifying,
  secondsLeft,
  lockout,
}: OtpStepProps) {
  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<OtpFormValues>({
    resolver: zodResolver(otpSchema),
    defaultValues: { otp: '' },
    mode: 'onSubmit',
  });

  // Independent countdown for the lockout window (Requirement 1.3).
  const lockoutTimer = useCountdown(0);
  const lockoutReset = lockoutTimer.reset;
  useEffect(() => {
    if (lockout?.isLocked && lockout.remainingSeconds !== undefined) {
      lockoutReset(lockout.remainingSeconds);
    }
  }, [lockout, lockoutReset]);

  const isExpired = secondsLeft <= 0;
  const isLocked = lockout?.isLocked ?? false;
  const inputsDisabled = isVerifying || isLocked;

  const lockoutMessage =
    isLocked && lockoutTimer.isRunning
      ? `Too many attempts. Try again in ${formatDuration(lockoutTimer.secondsLeft)}.`
      : (lockout?.message ?? null);

  return (
    <Stack
      component="form"
      spacing={2}
      onSubmit={(event) => void handleSubmit(({ otp }) => onVerify(otp))(event)}
      noValidate
    >
      {isLocked ? (
        <Alert severity="error" icon={<LockClockRoundedIcon />} role="alert">
          {lockoutMessage}
        </Alert>
      ) : lockout ? (
        <Alert severity="error" role="alert">
          {lockout.message}
        </Alert>
      ) : null}

      <TextField
        autoComplete="one-time-code"
        fullWidth
        placeholder={'0'.repeat(OTP_LENGTH)}
        error={Boolean(errors.otp)}
        helperText={errors.otp?.message ?? ' '}
        disabled={inputsDisabled}
        inputProps={{
          inputMode: 'numeric',
          maxLength: OTP_LENGTH,
          'aria-label': 'Verification code',
          style: {
            // Wide tracking reads as a code field without a per-digit input grid,
            // which keeps paste and password managers working.
            textAlign: 'center',
            fontSize: '1.5rem',
            fontWeight: 700,
            letterSpacing: '0.5em',
            textIndent: '0.5em',
          },
        }}
        {...register('otp')}
      />

      <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <Typography
          variant="body2"
          color={isExpired ? 'error.main' : 'text.secondary'}
          fontWeight={600}
          aria-live="polite"
        >
          {isExpired ? 'Code expired' : `Expires in ${formatDuration(secondsLeft)}`}
        </Typography>
        <Button type="button" size="small" onClick={onResend} disabled={!isExpired || isVerifying}>
          Resend code
        </Button>
      </Box>

      <Button
        type="submit"
        variant="contained"
        size="large"
        fullWidth
        disabled={inputsDisabled || isExpired}
      >
        {isVerifying ? 'Verifying…' : 'Verify & continue'}
      </Button>

      <Button
        type="button"
        variant="text"
        fullWidth
        onClick={onChangeNumber}
        disabled={isVerifying}
      >
        Change mobile number
      </Button>
    </Stack>
  );
}
