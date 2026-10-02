import { useEffect } from 'react';
import { Controller, useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { Alert, Box, Button, Stack, Typography } from '@mui/material';
import LockClockRoundedIcon from '@mui/icons-material/LockClockRounded';
import { env } from '@config/env';
import { OTP_LENGTH } from './constants';
import { formatDuration, type LockoutInfo } from './lockout';
import { useCountdown } from './useCountdown';
import { OtpInput } from './OtpInput';
import { otpSchema, type OtpFormValues } from './schemas';

interface OtpStepProps {
  onVerify: (otp: string) => void;
  onResend: () => void;
  onChangeNumber: () => void;
  isVerifying: boolean;
  /** True while a resend request is in flight. */
  isResending?: boolean;
  /** Remaining OTP validity in seconds (Requirement 1.4). */
  secondsLeft: number;
  /** Lockout details when the session is locked (Requirement 1.3). */
  lockout: LockoutInfo | null;
}

const ERROR_ID = 'otp-error';

/**
 * Step 2 of the login flow: enter the 6-digit OTP within the expiry window,
 * with a live countdown, resend once expired, and a lockout notice with the
 * remaining lockout time (Requirement 1.2–1.4). The code is submitted as soon
 * as the last digit is entered; the button stays for explicit submission.
 */
export function OtpStep({
  onVerify,
  onResend,
  onChangeNumber,
  isVerifying,
  isResending = false,
  secondsLeft,
  lockout,
}: OtpStepProps) {
  const {
    control,
    handleSubmit,
    setValue,
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

  // A rejected code is cleared so the next attempt starts from an empty field.
  useEffect(() => {
    if (lockout && !lockout.isLocked) setValue('otp', '');
  }, [lockout, setValue]);

  const isExpired = secondsLeft <= 0;
  const isLocked = lockout?.isLocked ?? false;
  const inputsDisabled = isVerifying || isLocked;

  const lockoutMessage =
    isLocked && lockoutTimer.isRunning
      ? `Too many attempts. Try again in ${formatDuration(lockoutTimer.secondsLeft)}.`
      : (lockout?.message ?? null);

  const submit = handleSubmit(({ otp }) => onVerify(otp));
  const fieldError = errors.otp?.message ?? (lockout && !isLocked ? lockout.message : null);

  return (
    <Stack component="form" spacing={2.5} onSubmit={(event) => void submit(event)} noValidate>
      {isLocked ? (
        <Alert severity="warning" icon={<LockClockRoundedIcon />} role="alert">
          {lockoutMessage}
        </Alert>
      ) : null}

      <Box>
        <Controller
          control={control}
          name="otp"
          render={({ field }) => (
            <OtpInput
              length={OTP_LENGTH}
              value={field.value}
              onChange={field.onChange}
              onBlur={field.onBlur}
              onComplete={() => {
                if (!inputsDisabled && !isExpired) void submit();
              }}
              disabled={inputsDisabled}
              error={Boolean(fieldError)}
              focusOnMount
              {...(fieldError ? { describedBy: ERROR_ID } : {})}
            />
          )}
        />
        {fieldError ? (
          <Typography
            id={ERROR_ID}
            role="alert"
            variant="body2"
            color="error.main"
            fontWeight={600}
            sx={{ mt: 1 }}
          >
            {fieldError}
          </Typography>
        ) : null}
      </Box>

      <Stack
        direction="row"
        justifyContent="space-between"
        alignItems="center"
        sx={{ minHeight: 36 }}
      >
        <Typography
          variant="body2"
          color={isExpired ? 'error.main' : 'text.secondary'}
          fontWeight={600}
          aria-live="polite"
        >
          {isExpired ? 'Code expired' : `Code expires in ${formatDuration(secondsLeft)}`}
        </Typography>
        <Button
          type="button"
          size="small"
          onClick={onResend}
          disabled={!isExpired || isVerifying || isResending}
        >
          {isResending ? 'Sending…' : 'Resend code'}
        </Button>
      </Stack>

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
        Use a different number
      </Button>

      {env.isProduction ? null : (
        <Alert severity="info" sx={{ fontSize: '0.8125rem' }}>
          Local dev sends no SMS. Read the code from the Auth Service log:
          <Box component="code" sx={{ display: 'block', mt: 0.5, wordBreak: 'break-all' }}>
            bash docker/otp.sh
          </Box>
        </Alert>
      )}
    </Stack>
  );
}
