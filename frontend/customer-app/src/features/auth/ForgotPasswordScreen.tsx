import { useCallback, useState } from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { useNavigate } from 'react-router-dom';
import { Alert, Box, Button, Stack, Typography } from '@mui/material';
import ArrowBackRoundedIcon from '@mui/icons-material/ArrowBackRounded';
import ArrowForwardRoundedIcon from '@mui/icons-material/ArrowForwardRounded';
import TaskAltRoundedIcon from '@mui/icons-material/TaskAltRounded';
import { brand } from '@lib/theme';
import type { ApiError } from '@api/client';
import { EMAIL_CODE_RESEND_SECONDS } from './constants';
import { emailAuthErrorMessage, emailAuthErrorSeverity } from './emailErrors';
import { getRetryAfterSeconds } from './lockout';
import { useCountdown } from './useCountdown';
import { useForgotPassword, useResetPassword } from './hooks';
import { useSignedInRedirect } from './useSignedInRedirect';
import { AuthLayout } from './AuthLayout';
import { LabelledTextField } from './AuthFields';
import { ResetPasswordStep } from './ResetPasswordStep';
import { forgotPasswordSchema, type ForgotPasswordFormValues } from './schemas';

type ResetStep = 'email' | 'reset' | 'done';

/**
 * Forgotten-password reset (email-auth Requirement 3).
 *
 * Three steps: (1) the account's email, which is always answered "code sent"
 * so the screen reveals nothing about whether it has an account, (2) the
 * emailed code and a new password, (3) a confirmation that leads back to email
 * sign-in. The reset ends every session of the account, so the customer signs
 * in again — on this device too.
 *
 * It stays open to a signed-in visitor: someone signed in here may still have
 * forgotten the password their profile asks for.
 */
export function ForgotPasswordScreen() {
  const navigate = useNavigate();
  const { state, forward } = useSignedInRedirect(false);
  const forgot = useForgotPassword();
  const reset = useResetPassword();
  const resendTimer = useCountdown(0);

  const [step, setStep] = useState<ResetStep>('email');
  const [email, setEmail] = useState(state.email ?? '');
  const startResendTimer = resendTimer.reset;

  const sendCode = useCallback(
    (address: string) => {
      forgot.mutate(
        { email: address },
        {
          onSuccess: () => {
            setEmail(address);
            setStep('reset');
            startResendTimer(EMAIL_CODE_RESEND_SECONDS);
          },
          onError: (error) => {
            if (error.status === 429) startResendTimer(getRetryAfterSeconds(error) ?? 0);
          },
        },
      );
    },
    [forgot, startResendTimer],
  );

  const handleReset = useCallback(
    ({ code, newPassword }: { code: string; newPassword: string }) => {
      forgot.reset();
      reset.mutate({ email, code, newPassword }, { onSuccess: () => setStep('done') });
    },
    [email, forgot, reset],
  );

  const handleResend = useCallback(() => {
    reset.reset();
    sendCode(email);
  }, [reset, sendCode, email]);

  const handleChangeEmail = useCallback(() => {
    reset.reset();
    forgot.reset();
    setStep('email');
  }, [reset, forgot]);

  const backToSignIn = () =>
    navigate('/login', {
      state: { ...forward, method: 'email', ...(email ? { email } : {}) },
    });

  return (
    <AuthLayout>
      {step !== 'done' ? (
        <Button
          size="small"
          startIcon={<ArrowBackRoundedIcon />}
          onClick={step === 'reset' ? handleChangeEmail : backToSignIn}
          disabled={reset.isPending || forgot.isPending}
          sx={{ ml: -1, mb: 1.5, color: 'text.secondary' }}
        >
          Back
        </Button>
      ) : null}

      {step === 'email' ? (
        <>
          <Typography variant="h3" component="h1">
            Reset your password
          </Typography>
          <Typography variant="body1" color="text.secondary" sx={{ mt: 1, mb: 3 }}>
            Enter the email you sign in with. We’ll send you a 6-digit code to set a new password.
          </Typography>
          <ForgotEmailStep
            defaultEmail={email}
            onSubmit={({ email: address }) => sendCode(address)}
            isSubmitting={forgot.isPending}
            error={forgot.isError ? forgot.error : null}
          />
        </>
      ) : step === 'reset' ? (
        <>
          <Typography variant="h3" component="h1">
            Set a new password
          </Typography>
          <Typography variant="body1" color="text.secondary" sx={{ mt: 1, mb: 3 }}>
            If{' '}
            <Box
              component="span"
              sx={{ color: 'text.primary', fontWeight: 700, wordBreak: 'break-all' }}
            >
              {email}
            </Box>{' '}
            has a HomeFix account, we’ve sent it a 6-digit code.
          </Typography>
          <ResetPasswordStep
            onSubmit={handleReset}
            onResend={handleResend}
            onChangeEmail={handleChangeEmail}
            isSubmitting={reset.isPending}
            isResending={forgot.isPending}
            resendSecondsLeft={resendTimer.secondsLeft}
            submitError={reset.isError ? reset.error : null}
            resendError={forgot.isError ? forgot.error : null}
          />
        </>
      ) : (
        <Stack spacing={2.5} alignItems="flex-start">
          <Box
            aria-hidden
            sx={{
              width: 56,
              height: 56,
              borderRadius: '50%',
              display: 'grid',
              placeItems: 'center',
              bgcolor: brand.greenSoft,
              color: brand.green,
            }}
          >
            <TaskAltRoundedIcon sx={{ fontSize: 30 }} />
          </Box>
          <Box>
            <Typography variant="h3" component="h1">
              Password changed
            </Typography>
            <Typography variant="body1" color="text.secondary" sx={{ mt: 1 }}>
              For your security we’ve signed you out on every device. Sign in with your new password
              to continue.
            </Typography>
          </Box>
          <Button variant="contained" size="large" fullWidth onClick={backToSignIn}>
            Sign in with email
          </Button>
        </Stack>
      )}
    </AuthLayout>
  );
}

/** Step 1: the email the account signs in with. */
function ForgotEmailStep({
  defaultEmail,
  onSubmit,
  isSubmitting,
  error,
}: {
  defaultEmail: string;
  onSubmit: (values: ForgotPasswordFormValues) => void;
  isSubmitting: boolean;
  error: ApiError | null;
}) {
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
      spacing={2.5}
      onSubmit={(event) => void handleSubmit(onSubmit)(event)}
      noValidate
    >
      {error ? (
        <Alert severity={emailAuthErrorSeverity(error)} role="alert">
          {emailAuthErrorMessage(error)}
        </Alert>
      ) : null}

      <LabelledTextField
        id="forgot-email"
        label="Email"
        type="email"
        autoComplete="email"
        placeholder="you@example.com"
        inputProps={{ inputMode: 'email', autoCapitalize: 'none', spellCheck: false }}
        error={Boolean(errors.email)}
        helperText={errors.email?.message}
        {...register('email')}
      />

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
    </Stack>
  );
}
