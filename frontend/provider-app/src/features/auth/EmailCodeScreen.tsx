import { useEffect } from 'react';
import { Navigate, useLocation, useNavigate } from 'react-router-dom';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { Alert, Button, Stack } from '@mui/material';
import { AuthLayout } from './AuthLayout';
import { CodeField } from './CodeField';
import { DevMailNotice } from './DevMailNotice';
import { EMAIL_AUTH_ERRORS, RESEND_COOLDOWN_SECONDS } from './constants';
import { useCountdown } from './useCountdown';
import { useResendEmailSignupCode, useVerifyEmailSignup } from './hooks';
import { ResendCodeRow } from './ResendCodeRow';
import {
  AUTH_ROUTES,
  DEFAULT_SIGNED_IN_ROUTE,
  readEmailCodeState,
  type EmailCodeLocationState,
  type LoginLocationState,
} from './navigation';
import { emailCodeSchema, type EmailCodeFormValues } from './schemas';

/**
 * Enter the 6-digit code emailed at sign-up (email-auth Requirement 1.6),
 * reached from the sign-up form or from an EMAIL_NOT_VERIFIED sign-in.
 *
 * A correct code activates the account and returns tokens, which go through
 * the same session path as OTP verification; the provider then lands where an
 * OTP sign-in does (the Dashboard, whose prompt walks a new provider through
 * their work profile). A new code can be asked for once a minute.
 */
export function EmailCodeScreen() {
  const location = useLocation();
  const details = readEmailCodeState(location.state);

  if (!details) {
    // Opened without an email (a typed or bookmarked URL): start over.
    return <Navigate to={AUTH_ROUTES.signUp} replace />;
  }

  return <EmailCodeForm details={details} />;
}

function EmailCodeForm({ details }: { details: EmailCodeLocationState }) {
  const navigate = useNavigate();
  const verify = useVerifyEmailSignup();
  const resend = useResendEmailSignupCode();
  const cooldown = useCountdown(0);

  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<EmailCodeFormValues>({
    resolver: zodResolver(emailCodeSchema),
    defaultValues: { code: '' },
    mode: 'onSubmit',
  });

  // A code has just been sent (or was under a minute ago): start the cooldown.
  const cooldownReset = cooldown.reset;
  const initialCooldown = details.resendInSeconds ?? RESEND_COOLDOWN_SECONDS;
  useEffect(() => {
    cooldownReset(initialCooldown);
  }, [cooldownReset, initialCooldown]);

  const onSubmit = ({ code }: EmailCodeFormValues) => {
    resend.reset();
    verify.mutate(
      {
        email: details.email,
        code,
        ...(details.mobileNumber ? { mobileNumber: details.mobileNumber } : {}),
        ...(details.displayName ? { displayName: details.displayName } : {}),
      },
      {
        onSuccess: () =>
          navigate(details.from?.pathname ?? DEFAULT_SIGNED_IN_ROUTE, { replace: true }),
      },
    );
  };

  const handleResend = () => {
    verify.reset();
    resend.mutate(
      { email: details.email },
      {
        onSuccess: () => cooldown.reset(RESEND_COOLDOWN_SECONDS),
        onError: (error) => {
          if (error.retryAfterSeconds !== undefined) cooldown.reset(error.retryAfterSeconds);
        },
      },
    );
  };

  // Only the sign-up form knows the name; a sign-in that hit EMAIL_NOT_VERIFIED does not.
  const cameFromSignUp = details.displayName !== undefined;
  const leave = () => {
    if (cameFromSignUp) {
      navigate(AUTH_ROUTES.signUp);
      return;
    }
    const state: LoginLocationState = { signInMethod: 'email', email: details.email };
    navigate(AUTH_ROUTES.login, { state });
  };

  const codeExpired = verify.error?.code === EMAIL_AUTH_ERRORS.codeExpired;

  return (
    <AuthLayout
      title="Verify your email"
      subtitle={`We sent a 6-digit code to ${details.email}. It is valid for 10 minutes.`}
    >
      <Stack
        component="form"
        spacing={2}
        onSubmit={(event) => void handleSubmit(onSubmit)(event)}
        noValidate
      >
        {verify.error ? (
          <Alert
            severity="error"
            role="alert"
            action={
              codeExpired ? (
                <Button
                  color="inherit"
                  size="small"
                  onClick={handleResend}
                  disabled={resend.isPending || cooldown.isRunning}
                >
                  Send a new code
                </Button>
              ) : undefined
            }
          >
            {verify.error.message}
          </Alert>
        ) : null}

        {resend.isSuccess ? (
          <Alert severity="success" role="status">
            We sent a new code to {details.email}.
          </Alert>
        ) : null}
        {resend.error ? (
          <Alert severity="error" role="alert">
            {resend.error.message}
          </Alert>
        ) : null}

        <CodeField
          {...register('code')}
          error={Boolean(errors.code)}
          helperText={errors.code?.message ?? ' '}
          disabled={verify.isPending}
        />

        <DevMailNotice />

        <ResendCodeRow
          onResend={handleResend}
          secondsLeft={cooldown.secondsLeft}
          isResending={resend.isPending}
          disabled={verify.isPending}
        />

        <Button
          type="submit"
          variant="contained"
          size="large"
          fullWidth
          disabled={verify.isPending}
        >
          {verify.isPending ? 'Verifying…' : 'Verify & continue'}
        </Button>

        <Button type="button" variant="text" fullWidth onClick={leave} disabled={verify.isPending}>
          {cameFromSignUp ? 'Use a different email' : 'Back to sign in'}
        </Button>
      </Stack>
    </AuthLayout>
  );
}
