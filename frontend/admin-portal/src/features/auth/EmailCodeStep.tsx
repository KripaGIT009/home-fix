import { useEffect } from 'react';
import { Alert, Stack } from '@mui/material';
import { isApiError } from '@api/client';
import { EMAIL_CODE_EXPIRY_SECONDS, EMAIL_CODE_RESEND_SECONDS } from './constants';
import { useResendEmailCode, useVerifyEmailSignup } from './hooks';
import { getLockoutInfo } from './lockout';
import { OtpStep } from './OtpStep';
import { useCountdown } from './useCountdown';

interface EmailCodeStepProps {
  /** The address the code was sent to. */
  email: string;
  /** The server's expiry for the code just sent. */
  expiresInSeconds: number;
  /** The name typed at sign-up, kept on the profile; absent when signing in. */
  displayName?: string;
  /** Back to the form that sent the code. */
  onBack: () => void;
  /** Label of the way back; defaults to "Use a different email". */
  backLabel?: string;
}

/**
 * The emailed sign-up code screen (email-auth Requirement 1.6), shared by the
 * agency applicant's sign-up and the EMAIL_NOT_VERIFIED sign-in (Requirement
 * 2.3). It reuses the OTP code field; a new code may be asked for a minute
 * after the last one rather than only once it expires, because an email that
 * never arrives would otherwise strand the person for ten minutes.
 *
 * Entering the right code activates the account and stores the session; the
 * screen hosting this step reacts to the session, so there is no callback.
 */
export function EmailCodeStep({
  email,
  expiresInSeconds,
  displayName,
  onBack,
  backLabel = 'Use a different email',
}: EmailCodeStepProps) {
  const verify = useVerifyEmailSignup();
  const resend = useResendEmailCode();
  const expiry = useCountdown(0);
  const cooldown = useCountdown(0);
  const resetExpiry = expiry.reset;
  const resetCooldown = cooldown.reset;

  // A code has just been sent when this step mounts.
  useEffect(() => {
    resetExpiry(expiresInSeconds || EMAIL_CODE_EXPIRY_SECONDS);
    resetCooldown(EMAIL_CODE_RESEND_SECONDS);
  }, [expiresInSeconds, resetExpiry, resetCooldown]);

  const handleVerify = (code: string) => {
    verify.mutate({ email, code, ...(displayName ? { displayName } : {}) });
  };

  const handleResend = () => {
    verify.reset();
    resend.mutate(email, {
      onSuccess: (data) => {
        resetExpiry(data.expiresInSeconds || EMAIL_CODE_EXPIRY_SECONDS);
        resetCooldown(EMAIL_CODE_RESEND_SECONDS);
      },
    });
  };

  // Only a failed verification describes the code itself; a refused resend
  // (429) leaves the current code perfectly usable, so it must not lock the field.
  const lockout = verify.isError ? getLockoutInfo(verify.error) : null;
  const resendError = resend.isError && isApiError(resend.error) ? resend.error.message : null;

  return (
    <Stack spacing={2}>
      {resendError ? <Alert severity="warning">{resendError}</Alert> : null}
      <OtpStep
        onVerify={handleVerify}
        onResend={handleResend}
        onChangeNumber={onBack}
        isVerifying={verify.isPending || resend.isPending}
        secondsLeft={expiry.secondsLeft}
        lockout={lockout}
        changeLabel={backLabel}
        resendInSeconds={cooldown.secondsLeft}
      />
    </Stack>
  );
}
