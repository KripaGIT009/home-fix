import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { Alert, Box, Button, InputAdornment, Stack, TextField, Typography } from '@mui/material';
import ArrowForwardRoundedIcon from '@mui/icons-material/ArrowForwardRounded';
import MailOutlineRoundedIcon from '@mui/icons-material/MailOutlineRounded';
import { isApiError } from '@api/client';
import { EMAIL_CODE_EXPIRY_SECONDS, EMAIL_CODE_RESEND_SECONDS, OTP_LENGTH } from './constants';
import { LabelledField, PasswordInput } from './FormFields';
import { useForgotPassword, useResetPassword } from './hooks';
import { formatDuration } from './lockout';
import { useCountdown } from './useCountdown';
import {
  forgotPasswordSchema,
  resetPasswordSchema,
  type ForgotPasswordFormValues,
  type ResetPasswordFormValues,
} from './schemas';

interface ForgotPasswordFlowProps {
  /** Prefilled when the sign-in form already holds an email. */
  initialEmail?: string;
  /** The password was replaced; back to sign-in. */
  onDone: () => void;
  /** Abandoned; back to sign-in. */
  onCancel: () => void;
}

/**
 * "Forgot password?" (email-auth Requirement 3): the account's email, then the
 * emailed code with a new password. The first step answers the same whether or
 * not the address has an account, so the second step says "if" rather than
 * confirming the address exists. A successful reset ends every session of the
 * account, so it ends here, back at sign-in, rather than signing anyone in.
 */
export function ForgotPasswordFlow({
  initialEmail = '',
  onDone,
  onCancel,
}: ForgotPasswordFlowProps) {
  const [email, setEmail] = useState<string | null>(null);
  const forgot = useForgotPassword();
  const reset = useResetPassword();
  const expiry = useCountdown(0);
  const cooldown = useCountdown(0);

  const emailForm = useForm<ForgotPasswordFormValues>({
    resolver: zodResolver(forgotPasswordSchema),
    defaultValues: { email: initialEmail.includes('@') ? initialEmail : '' },
    mode: 'onBlur',
  });

  const resetForm = useForm<ResetPasswordFormValues>({
    resolver: zodResolver(resetPasswordSchema),
    defaultValues: { code: '', newPassword: '', confirmPassword: '' },
    mode: 'onBlur',
  });

  const sendCode = (address: string) => {
    forgot.mutate(address, {
      onSuccess: (data) => {
        setEmail(address);
        expiry.reset(data.expiresInSeconds || EMAIL_CODE_EXPIRY_SECONDS);
        cooldown.reset(EMAIL_CODE_RESEND_SECONDS);
      },
    });
  };

  const handleReset = (values: ResetPasswordFormValues) => {
    if (!email) return;
    reset.mutate(
      { email, code: values.code, newPassword: values.newPassword },
      {
        onSuccess: onDone,
        onError: (error) => {
          // Codes that name a field go under it; anything else (an expired
          // code, a network failure) is shown above the form.
          if (error.code === 'INVALID_CODE') {
            resetForm.setError('code', { message: error.message });
          } else if (error.code === 'WEAK_PASSWORD') {
            resetForm.setError('newPassword', { message: error.message });
          }
        },
      },
    );
  };

  if (email === null) {
    const errorMessage = forgot.isError && isApiError(forgot.error) ? forgot.error.message : null;
    return (
      <Stack
        component="form"
        spacing={2}
        onSubmit={(event) =>
          void emailForm.handleSubmit((values) => sendCode(values.email.trim()))(event)
        }
        noValidate
      >
        {errorMessage ? <Alert severity="error">{errorMessage}</Alert> : null}

        <LabelledField label="Email">
          <TextField
            type="email"
            autoComplete="email"
            fullWidth
            placeholder="you@agency.com"
            error={Boolean(emailForm.formState.errors.email)}
            helperText={
              emailForm.formState.errors.email?.message ??
              'We will email you a code to set a new password.'
            }
            InputProps={{
              startAdornment: (
                <InputAdornment position="start">
                  <MailOutlineRoundedIcon fontSize="small" sx={{ color: 'text.secondary' }} />
                </InputAdornment>
              ),
            }}
            inputProps={{ 'aria-label': 'Email', autoCapitalize: 'none', spellCheck: false }}
            {...emailForm.register('email')}
          />
        </LabelledField>

        <Button
          type="submit"
          variant="contained"
          size="large"
          fullWidth
          disabled={forgot.isPending}
          endIcon={forgot.isPending ? undefined : <ArrowForwardRoundedIcon />}
        >
          {forgot.isPending ? 'Sending code…' : 'Send reset code'}
        </Button>
        <Button type="button" variant="text" fullWidth onClick={onCancel}>
          Back to sign in
        </Button>
      </Stack>
    );
  }

  const { errors } = resetForm.formState;
  const fieldCodes = ['INVALID_CODE', 'WEAK_PASSWORD'];
  const resetError =
    reset.isError && !fieldCodes.includes(reset.error.code) ? reset.error.message : null;
  const resendError = forgot.isError && isApiError(forgot.error) ? forgot.error.message : null;
  const isExpired = expiry.secondsLeft <= 0;

  return (
    <Stack
      component="form"
      spacing={2}
      onSubmit={(event) => void resetForm.handleSubmit(handleReset)(event)}
      noValidate
    >
      <Alert severity="info">
        If an account uses {email}, we have emailed it a {OTP_LENGTH}-digit code.
      </Alert>
      {resetError ? <Alert severity="error">{resetError}</Alert> : null}
      {resendError ? <Alert severity="warning">{resendError}</Alert> : null}

      <LabelledField label="Code">
        <TextField
          autoComplete="one-time-code"
          fullWidth
          placeholder={'0'.repeat(OTP_LENGTH)}
          error={Boolean(errors.code)}
          helperText={errors.code?.message ?? ' '}
          inputProps={{
            inputMode: 'numeric',
            maxLength: OTP_LENGTH,
            'aria-label': 'Reset code',
            style: { fontSize: '1.25rem', fontWeight: 700, letterSpacing: '0.4em' },
          }}
          {...resetForm.register('code')}
        />
      </LabelledField>

      <LabelledField label="New password">
        <PasswordInput
          registration={resetForm.register('newPassword')}
          ariaLabel="New password"
          autoComplete="new-password"
          error={errors.newPassword?.message}
          helperText="8–72 characters, with a letter and a number."
        />
      </LabelledField>

      <LabelledField label="Confirm new password">
        <PasswordInput
          registration={resetForm.register('confirmPassword')}
          ariaLabel="Confirm new password"
          autoComplete="new-password"
          error={errors.confirmPassword?.message}
        />
      </LabelledField>

      <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <Typography
          variant="body2"
          color={isExpired ? 'error.main' : 'text.secondary'}
          fontWeight={600}
          aria-live="polite"
        >
          {isExpired ? 'Code expired' : `Expires in ${formatDuration(expiry.secondsLeft)}`}
        </Typography>
        <Button
          type="button"
          size="small"
          onClick={() => sendCode(email)}
          disabled={cooldown.isRunning || forgot.isPending || reset.isPending}
        >
          {cooldown.isRunning ? `Resend in ${formatDuration(cooldown.secondsLeft)}` : 'Resend code'}
        </Button>
      </Box>

      <Button
        type="submit"
        variant="contained"
        size="large"
        fullWidth
        disabled={reset.isPending || isExpired}
      >
        {reset.isPending ? 'Saving…' : 'Set new password'}
      </Button>
      <Button type="button" variant="text" fullWidth onClick={onCancel} disabled={reset.isPending}>
        Back to sign in
      </Button>
    </Stack>
  );
}
