import { useEffect } from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { Alert, AlertTitle, Box, Button, Link, Stack, Typography } from '@mui/material';
import ArrowForwardRoundedIcon from '@mui/icons-material/ArrowForwardRounded';
import LockClockRoundedIcon from '@mui/icons-material/LockClockRounded';
import MarkEmailUnreadRoundedIcon from '@mui/icons-material/MarkEmailUnreadRounded';
import type { ApiError } from '@api/client';
import { AUTH_ERROR, EMAIL_CODE_RESEND_SECONDS } from './constants';
import { emailAuthErrorMessage, emailAuthErrorSeverity } from './emailErrors';
import { formatDuration, getRetryAfterSeconds } from './lockout';
import { useCountdown } from './useCountdown';
import { usePasswordLogin, useResendSignUpCode } from './hooks';
import { LabelledTextField, PasswordField } from './AuthFields';
import { emailSignInSchema, type EmailSignInFormValues } from './schemas';

/** What the code screen needs once a new sign-up code is on its way. */
export interface CodeSentInfo {
  expiresInSeconds?: number;
  resendAfterSeconds?: number;
}

interface EmailSignInFormProps {
  /** Prefill, e.g. after a password reset. */
  defaultEmail?: string;
  /** A new sign-up code was sent to an unverified account: open the code screen. */
  onCodeSent: (email: string, info: CodeSentInfo) => void;
  onForgotPassword: (email: string) => void;
  onCreateAccount: () => void;
  /** True while another sign-in method (Google) is in flight. */
  disabled?: boolean;
}

/**
 * Email and password sign-in (email-auth Requirement 2).
 *
 * Owns its own mutations: a successful sign-in stores the session exactly as
 * OTP verification does, and the login screen's redirect takes it from there.
 * Each refusal is explained in place — wrong credentials, a lockout with a
 * live countdown, a disabled account — and an account whose sign-up code was
 * never entered (EMAIL_NOT_VERIFIED) is offered a new code.
 */
export function EmailSignInForm({
  defaultEmail = '',
  onCodeSent,
  onForgotPassword,
  onCreateAccount,
  disabled = false,
}: EmailSignInFormProps) {
  const login = usePasswordLogin();
  const resend = useResendSignUpCode();
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

  // Started once per refusal: the form re-renders every second while the
  // countdown runs, and the error object is stable across those renders.
  const loginError = login.isError ? login.error : null;
  const lockReset = lockTimer.reset;
  useEffect(() => {
    if (loginError?.code === AUTH_ERROR.ACCOUNT_LOCKED) {
      lockReset(getRetryAfterSeconds(loginError) ?? 0);
    }
  }, [loginError, lockReset]);

  const submit = handleSubmit(({ email, password }) => {
    resend.reset();
    login.mutate({ identifier: email, password });
  });

  // The address the server answered about, not whatever the field holds now.
  const signInEmail = login.variables?.identifier ?? getValues('email');

  const handleSendCode = () => {
    resend.mutate(
      { email: signInEmail },
      {
        onSuccess: (data) => onCodeSent(signInEmail, { expiresInSeconds: data.expiresInSeconds }),
        onError: (error) => {
          // A code went out under a minute ago and is still good: open the code
          // screen anyway, with resend waiting out the rest of that minute.
          const wait = getRetryAfterSeconds(error);
          if (
            error.code === AUTH_ERROR.TOO_MANY_REQUESTS &&
            wait !== undefined &&
            wait <= EMAIL_CODE_RESEND_SECONDS
          ) {
            onCodeSent(signInEmail, { resendAfterSeconds: wait });
          }
        },
      },
    );
  };

  const isLocked = loginError?.code === AUTH_ERROR.ACCOUNT_LOCKED && lockTimer.isRunning;
  const busy = login.isPending || resend.isPending || disabled;

  return (
    <Stack component="form" spacing={2.5} onSubmit={(event) => void submit(event)} noValidate>
      {loginError ? (
        <SignInErrorNotice
          error={loginError}
          lockSecondsLeft={lockTimer.secondsLeft}
          onSendCode={handleSendCode}
          isSendingCode={resend.isPending}
        />
      ) : null}
      {resend.isError ? (
        <Alert severity={emailAuthErrorSeverity(resend.error)} role="alert">
          {emailAuthErrorMessage(resend.error)}
        </Alert>
      ) : null}

      <LabelledTextField
        id="login-email"
        label="Email"
        type="email"
        autoComplete="email"
        placeholder="you@example.com"
        inputProps={{ inputMode: 'email', autoCapitalize: 'none', spellCheck: false }}
        error={Boolean(errors.email)}
        helperText={errors.email?.message}
        {...register('email')}
      />

      <Box>
        <PasswordField
          id="login-password"
          label="Password"
          autoComplete="current-password"
          error={Boolean(errors.password)}
          helperText={errors.password?.message}
          {...register('password')}
        />
        <Box sx={{ display: 'flex', justifyContent: 'flex-end', mt: 1 }}>
          <Link
            component="button"
            type="button"
            variant="body2"
            underline="hover"
            fontWeight={600}
            onClick={() => onForgotPassword(getValues('email').trim())}
          >
            Forgot password?
          </Link>
        </Box>
      </Box>

      <Button
        type="submit"
        variant="contained"
        size="large"
        fullWidth
        disabled={busy || isLocked}
        endIcon={login.isPending ? undefined : <ArrowForwardRoundedIcon />}
      >
        {login.isPending ? 'Signing in…' : 'Sign in'}
      </Button>

      <Typography variant="body2" color="text.secondary" align="center">
        New here?{' '}
        <Link
          component="button"
          type="button"
          variant="body2"
          underline="hover"
          fontWeight={700}
          onClick={onCreateAccount}
          sx={{ verticalAlign: 'baseline' }}
        >
          Create account
        </Link>
      </Typography>
    </Stack>
  );
}

/** Explains a refused sign-in in the customer's terms. */
function SignInErrorNotice({
  error,
  lockSecondsLeft,
  onSendCode,
  isSendingCode,
}: {
  error: ApiError;
  lockSecondsLeft: number;
  onSendCode: () => void;
  isSendingCode: boolean;
}) {
  switch (error.code) {
    case AUTH_ERROR.INVALID_CREDENTIALS:
      return (
        <Alert severity="error" role="alert">
          That email and password don’t match. Check them and try again, or reset your password.
        </Alert>
      );
    case AUTH_ERROR.ACCOUNT_LOCKED:
      // Once a known wait is over there is nothing left to say.
      if (lockSecondsLeft <= 0 && getRetryAfterSeconds(error) !== undefined) return null;
      return (
        <Alert severity="warning" icon={<LockClockRoundedIcon />} role="alert">
          {lockSecondsLeft > 0
            ? `Too many failed attempts. Try again in ${formatDuration(lockSecondsLeft)}.`
            : error.message}
        </Alert>
      );
    case AUTH_ERROR.ACCOUNT_DISABLED:
      return (
        <Alert severity="error" role="alert">
          <AlertTitle>This account is disabled</AlertTitle>
          It can’t be used to sign in. Contact HomeFix support if you think this is a mistake.
        </Alert>
      );
    case AUTH_ERROR.EMAIL_NOT_VERIFIED:
      return (
        <Alert
          severity="info"
          icon={<MarkEmailUnreadRoundedIcon />}
          role="alert"
          action={
            <Button
              color="inherit"
              size="small"
              onClick={onSendCode}
              disabled={isSendingCode}
              sx={{ whiteSpace: 'nowrap' }}
            >
              {isSendingCode ? 'Sending…' : 'Send a new code'}
            </Button>
          }
        >
          <AlertTitle>Verify your email first</AlertTitle>
          You haven’t entered the code we emailed when you signed up. We can send you a new one.
        </Alert>
      );
    default:
      return (
        <Alert severity={emailAuthErrorSeverity(error)} role="alert">
          {emailAuthErrorMessage(error)}
        </Alert>
      );
  }
}
