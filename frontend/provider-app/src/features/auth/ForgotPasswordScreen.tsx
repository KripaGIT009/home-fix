import { useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { Alert, Box, Button, Stack, TextField, Typography } from '@mui/material';
import type { ApiError } from '@api/client';
import ArrowForwardRoundedIcon from '@mui/icons-material/ArrowForwardRounded';
import CheckCircleRoundedIcon from '@mui/icons-material/CheckCircleRounded';
import { AuthLayout } from './AuthLayout';
import { CodeField } from './CodeField';
import { DevMailNotice } from './DevMailNotice';
import { EMAIL_AUTH_ERRORS, RESEND_COOLDOWN_SECONDS } from './constants';
import { useCountdown } from './useCountdown';
import { useRequestPasswordReset, useResetPassword } from './hooks';
import { PasswordField } from './PasswordField';
import { ResendCodeRow } from './ResendCodeRow';
import { AUTH_ROUTES, readForgotPasswordState, type LoginLocationState } from './navigation';
import {
  forgotPasswordSchema,
  PASSWORD_HINT,
  resetPasswordSchema,
  type ForgotPasswordFormValues,
  type ResetPasswordFormValues,
} from './schemas';

type Step = 'email' | 'reset' | 'done';

const HEADINGS: Record<Step, { title: string; subtitle: (email: string) => string }> = {
  email: {
    title: 'Reset your password',
    subtitle: () => 'Enter the email you sign in with and we will send you a code.',
  },
  reset: {
    title: 'Choose a new password',
    subtitle: (email) => `If ${email} has a HomeFix account, we sent it a 6-digit code.`,
  },
  done: {
    title: 'Password changed',
    subtitle: () => 'Sign in with your new password.',
  },
};

/**
 * Forgotten password (email-auth Requirement 3): (1) the email to send a reset
 * code to, (2) the code and a new password typed twice, (3) done. The Auth
 * Service answers step 1 identically whether or not the email has an account,
 * so the screen never says which. A reset ends every session of the account,
 * so the way out is back to email sign-in.
 */
export function ForgotPasswordScreen() {
  const navigate = useNavigate();
  const location = useLocation();
  const initialEmail = readForgotPasswordState(location.state).email ?? '';

  const [step, setStep] = useState<Step>('email');
  const [email, setEmail] = useState(initialEmail);
  // Set once a second code has gone out, to confirm the resend.
  const [resent, setResent] = useState(false);
  const requestReset = useRequestPasswordReset();
  const reset = useResetPassword();
  const cooldown = useCountdown(0);

  const sendCode = (address: string, onSent?: () => void) => {
    requestReset.mutate(
      { email: address },
      {
        onSuccess: () => {
          setEmail(address);
          cooldown.reset(RESEND_COOLDOWN_SECONDS);
          onSent?.();
        },
        onError: (error) => {
          if (error.retryAfterSeconds !== undefined) cooldown.reset(error.retryAfterSeconds);
        },
      },
    );
  };

  const backToSignIn = () => {
    const state: LoginLocationState = {
      signInMethod: 'email',
      ...(email ? { email } : {}),
      ...(step === 'done' ? { notice: 'Password changed. Sign in with your new password.' } : {}),
    };
    navigate(AUTH_ROUTES.login, { state });
  };

  const heading = HEADINGS[step];

  return (
    <AuthLayout title={heading.title} subtitle={heading.subtitle(email)}>
      {step === 'email' ? (
        <EmailStep
          defaultEmail={initialEmail}
          onSubmit={({ email: address }) =>
            sendCode(address, () => {
              setResent(false);
              setStep('reset');
            })
          }
          isSubmitting={requestReset.isPending}
          errorMessage={requestReset.error?.message ?? null}
          onCancel={backToSignIn}
        />
      ) : step === 'reset' ? (
        <ResetStep
          onSubmit={({ code, newPassword }) =>
            reset.mutate({ email, code, newPassword }, { onSuccess: () => setStep('done') })
          }
          isSubmitting={reset.isPending}
          error={reset.error}
          onResend={() => {
            reset.reset();
            sendCode(email, () => setResent(true));
          }}
          resendSecondsLeft={cooldown.secondsLeft}
          isResending={requestReset.isPending}
          resendError={requestReset.error?.message ?? null}
          resent={resent}
          onChangeEmail={() => {
            reset.reset();
            requestReset.reset();
            setStep('email');
          }}
        />
      ) : (
        <Stack spacing={2} alignItems="center" textAlign="center">
          <CheckCircleRoundedIcon color="success" sx={{ fontSize: 56 }} aria-hidden />
          <Typography variant="body1">
            Your password has been changed and you have been signed out on every device.
          </Typography>
          <Button
            variant="contained"
            size="large"
            fullWidth
            onClick={backToSignIn}
            endIcon={<ArrowForwardRoundedIcon />}
          >
            Sign in with email
          </Button>
        </Stack>
      )}
    </AuthLayout>
  );
}

interface EmailStepProps {
  defaultEmail: string;
  onSubmit: (values: ForgotPasswordFormValues) => void;
  isSubmitting: boolean;
  errorMessage: string | null;
  onCancel: () => void;
}

/** Step 1: where to send the reset code. */
function EmailStep({
  defaultEmail,
  onSubmit,
  isSubmitting,
  errorMessage,
  onCancel,
}: EmailStepProps) {
  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<ForgotPasswordFormValues>({
    resolver: zodResolver(forgotPasswordSchema),
    defaultValues: { email: defaultEmail },
    mode: 'onBlur',
  });

  return (
    <Stack
      component="form"
      spacing={2}
      onSubmit={(event) => void handleSubmit(onSubmit)(event)}
      noValidate
    >
      {errorMessage ? (
        <Alert severity="error" role="alert">
          {errorMessage}
        </Alert>
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

      <Button
        type="submit"
        variant="contained"
        size="large"
        fullWidth
        disabled={isSubmitting}
        endIcon={isSubmitting ? undefined : <ArrowForwardRoundedIcon />}
      >
        {isSubmitting ? 'Sending code…' : 'Send code'}
      </Button>

      <Button type="button" variant="text" fullWidth onClick={onCancel} disabled={isSubmitting}>
        Back to sign in
      </Button>
    </Stack>
  );
}

interface ResetStepProps {
  onSubmit: (values: ResetPasswordFormValues) => void;
  isSubmitting: boolean;
  error: ApiError | null;
  onResend: () => void;
  resendSecondsLeft: number;
  isResending: boolean;
  resendError: string | null;
  resent: boolean;
  onChangeEmail: () => void;
}

/** Step 2: the emailed code and the new password, typed twice. */
function ResetStep({
  onSubmit,
  isSubmitting,
  error,
  onResend,
  resendSecondsLeft,
  isResending,
  resendError,
  resent,
  onChangeEmail,
}: ResetStepProps) {
  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<ResetPasswordFormValues>({
    resolver: zodResolver(resetPasswordSchema),
    defaultValues: { code: '', newPassword: '', confirmPassword: '' },
    mode: 'onSubmit',
  });

  const codeExpired = error?.code === EMAIL_AUTH_ERRORS.codeExpired;

  return (
    <Stack
      component="form"
      spacing={2}
      onSubmit={(event) => void handleSubmit(onSubmit)(event)}
      noValidate
    >
      {error ? (
        <Alert
          severity="error"
          role="alert"
          action={
            codeExpired ? (
              <Button
                color="inherit"
                size="small"
                onClick={onResend}
                disabled={isResending || resendSecondsLeft > 0}
              >
                Send a new code
              </Button>
            ) : undefined
          }
        >
          {error.message}
        </Alert>
      ) : null}
      {resendError ? (
        <Alert severity="error" role="alert">
          {resendError}
        </Alert>
      ) : resent && !error ? (
        <Alert severity="success" role="status">
          Code sent. Check your inbox.
        </Alert>
      ) : null}

      <CodeField
        {...register('code')}
        error={Boolean(errors.code)}
        helperText={errors.code?.message ?? ' '}
        disabled={isSubmitting}
      />

      <DevMailNotice />

      <ResendCodeRow
        onResend={onResend}
        secondsLeft={resendSecondsLeft}
        isResending={isResending}
        disabled={isSubmitting}
      />

      <Box>
        <Typography variant="subtitle2" sx={{ mb: 0.75, color: 'text.primary' }}>
          New password
        </Typography>
        <PasswordField
          autoComplete="new-password"
          ariaLabel="New password"
          error={Boolean(errors.newPassword)}
          helperText={errors.newPassword?.message ?? PASSWORD_HINT}
          {...register('newPassword')}
        />
      </Box>

      <Box>
        <Typography variant="subtitle2" sx={{ mb: 0.75, color: 'text.primary' }}>
          Confirm new password
        </Typography>
        <PasswordField
          autoComplete="new-password"
          ariaLabel="Confirm new password"
          error={Boolean(errors.confirmPassword)}
          helperText={errors.confirmPassword?.message}
          {...register('confirmPassword')}
        />
      </Box>

      <Button type="submit" variant="contained" size="large" fullWidth disabled={isSubmitting}>
        {isSubmitting ? 'Saving…' : 'Change password'}
      </Button>

      <Button
        type="button"
        variant="text"
        fullWidth
        onClick={onChangeEmail}
        disabled={isSubmitting}
      >
        Use a different email
      </Button>
    </Stack>
  );
}
