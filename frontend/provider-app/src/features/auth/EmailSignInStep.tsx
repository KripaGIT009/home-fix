import { useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { Alert, AlertTitle, Box, Button, Link, Stack, TextField, Typography } from '@mui/material';
import ArrowForwardRoundedIcon from '@mui/icons-material/ArrowForwardRounded';
import LockClockRoundedIcon from '@mui/icons-material/LockClockRounded';
import type { ApiError } from '@api/client';
import { EMAIL_AUTH_ERRORS, RESEND_COOLDOWN_SECONDS } from './constants';
import { formatDuration, getLockoutInfo } from './lockout';
import { useCountdown } from './useCountdown';
import { usePasswordLogin, useResendEmailSignupCode } from './hooks';
import { PasswordField } from './PasswordField';
import {
  AUTH_ROUTES,
  type EmailCodeLocationState,
  type ForgotPasswordLocationState,
} from './navigation';
import { emailSignInSchema, type EmailSignInFormValues } from './schemas';

interface EmailSignInStepProps {
  /** Prefills the email, e.g. after a password reset. */
  defaultEmail?: string;
  /** The protected route to return to, threaded through to the code screen. */
  from?: { pathname?: string };
}

/**
 * The login screen's Email tab (email-auth Requirement 2): email + password,
 * a "Forgot password?" link, and the sign-in errors explained —
 * INVALID_CREDENTIALS, ACCOUNT_LOCKED with the remaining wait, ACCOUNT_DISABLED,
 * and EMAIL_NOT_VERIFIED, which offers a new sign-up code and opens the code
 * screen. A successful sign-in sets the session; LoginScreen then navigates.
 */
export function EmailSignInStep({ defaultEmail = '', from }: EmailSignInStepProps) {
  const navigate = useNavigate();
  const login = usePasswordLogin();
  const resend = useResendEmailSignupCode();
  const lockTimer = useCountdown(0);

  const {
    register,
    handleSubmit,
    getValues,
    formState: { errors },
  } = useForm<EmailSignInFormValues>({
    resolver: zodResolver(emailSignInSchema),
    defaultValues: { email: defaultEmail, password: '' },
    mode: 'onBlur',
  });

  // ACCOUNT_LOCKED carries the wait in Retry-After (and, as a fallback, in
  // its message): count it down and hold the form until it is over.
  const lockReset = lockTimer.reset;
  useEffect(() => {
    if (login.error?.code !== EMAIL_AUTH_ERRORS.accountLocked) return;
    const seconds = login.error.retryAfterSeconds ?? getLockoutInfo(login.error).remainingSeconds;
    if (seconds !== undefined) lockReset(seconds);
  }, [login.error, lockReset]);

  const onSubmit = ({ email, password }: EmailSignInFormValues) => {
    resend.reset();
    login.mutate({ identifier: email, password });
  };

  const openCodeScreen = (email: string, resendInSeconds: number) => {
    const state: EmailCodeLocationState = { email, resendInSeconds, ...(from ? { from } : {}) };
    navigate(AUTH_ROUTES.verifyEmail, { state });
  };

  // The password was right but the sign-up code was never entered: send a new
  // one and open the code screen. A 429 means a code went out under a minute
  // ago, which is still valid, so the code screen opens all the same.
  const sendNewCode = () => {
    const email = login.variables?.identifier;
    if (!email) return;
    resend.mutate(
      { email },
      {
        onSuccess: () => openCodeScreen(email, RESEND_COOLDOWN_SECONDS),
        onError: (error) => {
          if (error.status === 429) {
            openCodeScreen(email, error.retryAfterSeconds ?? RESEND_COOLDOWN_SECONDS);
          }
        },
      },
    );
  };

  const openForgotPassword = () => {
    const state: ForgotPasswordLocationState = { email: getValues('email').trim() };
    navigate(AUTH_ROUTES.forgotPassword, { state });
  };

  const isLocked = login.error?.code === EMAIL_AUTH_ERRORS.accountLocked && lockTimer.isRunning;

  return (
    <Stack
      component="form"
      spacing={2}
      onSubmit={(event) => void handleSubmit(onSubmit)(event)}
      noValidate
    >
      {login.error ? (
        <SignInErrorAlert
          error={login.error}
          lockSecondsLeft={lockTimer.secondsLeft}
          onSendNewCode={sendNewCode}
          isSendingCode={resend.isPending}
          sendCodeError={resend.error?.status === 429 ? null : (resend.error?.message ?? null)}
        />
      ) : null}

      <Box>
        <Typography variant="subtitle2" sx={{ mb: 0.75, color: 'text.primary' }}>
          Email
        </Typography>
        <TextField
          type="email"
          autoComplete="email"
          fullWidth
          placeholder="you@example.com"
          error={Boolean(errors.email)}
          helperText={errors.email?.message}
          inputProps={{ inputMode: 'email', 'aria-label': 'Email' }}
          {...register('email')}
        />
      </Box>

      <Box>
        <Box
          sx={{
            display: 'flex',
            justifyContent: 'space-between',
            alignItems: 'baseline',
            mb: 0.75,
          }}
        >
          <Typography variant="subtitle2" sx={{ color: 'text.primary' }}>
            Password
          </Typography>
          <Link
            component="button"
            type="button"
            variant="body2"
            underline="hover"
            fontWeight={600}
            onClick={openForgotPassword}
          >
            Forgot password?
          </Link>
        </Box>
        <PasswordField
          autoComplete="current-password"
          ariaLabel="Password"
          error={Boolean(errors.password)}
          helperText={errors.password?.message}
          {...register('password')}
        />
      </Box>

      <Button
        type="submit"
        variant="contained"
        size="large"
        fullWidth
        disabled={login.isPending || isLocked}
        endIcon={login.isPending ? undefined : <ArrowForwardRoundedIcon />}
      >
        {login.isPending ? 'Signing in…' : 'Sign in'}
      </Button>
    </Stack>
  );
}

interface SignInErrorAlertProps {
  error: ApiError;
  lockSecondsLeft: number;
  onSendNewCode: () => void;
  isSendingCode: boolean;
  sendCodeError: string | null;
}

/** What went wrong with a password sign-in, and what to do about it. */
function SignInErrorAlert({
  error,
  lockSecondsLeft,
  onSendNewCode,
  isSendingCode,
  sendCodeError,
}: SignInErrorAlertProps) {
  switch (error.code) {
    case EMAIL_AUTH_ERRORS.emailNotVerified:
      return (
        <Alert
          severity="warning"
          role="alert"
          action={
            <Button color="inherit" size="small" onClick={onSendNewCode} disabled={isSendingCode}>
              {isSendingCode ? 'Sending…' : 'Send a new code'}
            </Button>
          }
        >
          Your email is not verified yet. Finish signing up with the 6-digit code we email you.
          {sendCodeError ? (
            <Box component="span" sx={{ display: 'block', mt: 0.5, fontWeight: 600 }}>
              {sendCodeError}
            </Box>
          ) : null}
        </Alert>
      );
    case EMAIL_AUTH_ERRORS.accountLocked:
      return lockSecondsLeft > 0 ? (
        <Alert severity="error" icon={<LockClockRoundedIcon />} role="alert">
          Too many failed sign-in attempts. Try again in {formatDuration(lockSecondsLeft)}.
        </Alert>
      ) : (
        <Alert severity="info" role="status">
          You can try signing in again now.
        </Alert>
      );
    case EMAIL_AUTH_ERRORS.invalidCredentials:
      return (
        <Alert severity="error" role="alert">
          Incorrect email or password.
        </Alert>
      );
    case EMAIL_AUTH_ERRORS.accountDisabled:
      return (
        <Alert severity="error" role="alert">
          <AlertTitle>Account disabled</AlertTitle>
          {error.message}
        </Alert>
      );
    default:
      return (
        <Alert severity="error" role="alert">
          {error.message}
        </Alert>
      );
  }
}
