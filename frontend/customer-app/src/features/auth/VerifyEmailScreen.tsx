import { useCallback, useEffect } from 'react';
import { Navigate, useNavigate } from 'react-router-dom';
import { Box, Button, Typography } from '@mui/material';
import ArrowBackRoundedIcon from '@mui/icons-material/ArrowBackRounded';
import { EMAIL_CODE_RESEND_SECONDS } from './constants';
import { getRetryAfterSeconds } from './lockout';
import { useCountdown } from './useCountdown';
import { useResendSignUpCode, useVerifyEmailSignUp } from './hooks';
import { useSignedInRedirect } from './useSignedInRedirect';
import { AuthLayout } from './AuthLayout';
import { EmailCodeStep } from './EmailCodeStep';

/**
 * The emailed sign-up code (email-auth Requirements 1.6 and 1.8), reached after
 * sign-up or from an EMAIL_NOT_VERIFIED sign-in.
 *
 * A correct code signs the customer in through the same store path OTP
 * verification uses, and the shared redirect then leaves for the page that
 * sent them to sign in (or Home). The address arrives in router state; opened
 * without one (a reload in a fresh tab, a typed URL) there is nothing to
 * verify, so it goes back to sign-up.
 */
export function VerifyEmailScreen() {
  const navigate = useNavigate();
  const { state, forward } = useSignedInRedirect();
  const verify = useVerifyEmailSignUp();
  const resend = useResendSignUpCode();
  const resendTimer = useCountdown(0);

  const email = state.email;
  const initialWait = state.resendAfterSeconds ?? EMAIL_CODE_RESEND_SECONDS;
  const startResendTimer = resendTimer.reset;

  // A code has just been sent: the next one is a minute away.
  useEffect(() => {
    startResendTimer(initialWait);
  }, [initialWait, startResendTimer]);

  const handleVerify = useCallback(
    (code: string) => {
      if (!email) return;
      resend.reset();
      verify.mutate({ email, code });
    },
    [email, resend, verify],
  );

  const handleResend = useCallback(() => {
    if (!email) return;
    verify.reset();
    resend.mutate(
      { email },
      {
        onSuccess: () => startResendTimer(EMAIL_CODE_RESEND_SECONDS),
        // Rate-limited: wait out what the service asks for before the next try.
        onError: (error) => {
          if (error.status === 429) startResendTimer(getRetryAfterSeconds(error) ?? 0);
        },
      },
    );
  }, [email, resend, verify, startResendTimer]);

  if (!email) {
    return <Navigate to="/signup" replace state={forward} />;
  }

  const validMinutes = state.expiresInSeconds ? Math.round(state.expiresInSeconds / 60) : null;

  return (
    <AuthLayout>
      <Button
        size="small"
        startIcon={<ArrowBackRoundedIcon />}
        onClick={() => navigate('/signup', { state: forward })}
        disabled={verify.isPending}
        sx={{ ml: -1, mb: 1.5, color: 'text.secondary' }}
      >
        Back
      </Button>

      <Typography variant="h3" component="h1">
        Check your email
      </Typography>
      <Typography variant="body1" color="text.secondary" sx={{ mt: 1, mb: 3 }}>
        We sent a 6-digit code to{' '}
        <Box
          component="span"
          sx={{ color: 'text.primary', fontWeight: 700, wordBreak: 'break-all' }}
        >
          {email}
        </Box>
        {validMinutes ? `. It’s valid for ${validMinutes} minutes.` : '.'} If that address already
        has a HomeFix account, we’ve emailed you about it instead — sign in or reset your password.
      </Typography>

      <EmailCodeStep
        idPrefix="signup"
        onVerify={handleVerify}
        onResend={handleResend}
        isVerifying={verify.isPending}
        isResending={resend.isPending}
        resendSecondsLeft={resendTimer.secondsLeft}
        verifyError={verify.isError ? verify.error : null}
        resendError={resend.isError ? resend.error : null}
        submitLabel="Verify & continue"
      />
    </AuthLayout>
  );
}
