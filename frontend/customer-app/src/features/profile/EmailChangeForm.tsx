import { useCallback, useEffect, useState } from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { Alert, Box, Button, Stack, Typography } from '@mui/material';
import type { ApiError } from '@api/client';
import { AUTH_ERROR, EMAIL_CODE_RESEND_SECONDS } from '@features/auth/constants';
import { emailAuthErrorMessage, emailAuthErrorSeverity } from '@features/auth/emailErrors';
import { getRetryAfterSeconds } from '@features/auth/lockout';
import { useCountdown } from '@features/auth/useCountdown';
import { LabelledTextField, PasswordField } from '@features/auth/AuthFields';
import { EmailCodeStep } from '@features/auth/EmailCodeStep';
import { useConfirmEmailChange, useRequestEmailChange } from './hooks';
import { emailChangeSchema, type EmailChangeFormValues } from './schemas';
import type { AccountCredentials, EmailChangePayload } from './api';

interface EmailChangeFormProps {
  account: AccountCredentials;
  /** The new address is verified and saved. */
  onDone: (account: AccountCredentials) => void;
  onCancel: () => void;
}

/**
 * Add or change the account's email (email-auth Requirement 4.1–4.2): the new
 * address, plus the current password when the account has one, then the code
 * emailed to that address. Nothing changes until the code is entered.
 */
export function EmailChangeForm({ account, onDone, onCancel }: EmailChangeFormProps) {
  const request = useRequestEmailChange();
  const confirm = useConfirmEmailChange();
  const resendTimer = useCountdown(0);
  // Kept for "Resend code", which asks again with the same address and password.
  const [pending, setPending] = useState<EmailChangePayload | null>(null);
  const startResendTimer = resendTimer.reset;

  const sendCode = useCallback(
    (payload: EmailChangePayload) => {
      request.mutate(payload, {
        onSuccess: () => {
          setPending(payload);
          startResendTimer(EMAIL_CODE_RESEND_SECONDS);
        },
        onError: (error) => {
          if (error.status === 429) startResendTimer(getRetryAfterSeconds(error) ?? 0);
        },
      });
    },
    [request, startResendTimer],
  );

  const handleVerify = useCallback(
    (code: string) => {
      request.reset();
      confirm.mutate({ code }, { onSuccess: onDone });
    },
    [request, confirm, onDone],
  );

  const handleResend = useCallback(() => {
    if (!pending) return;
    confirm.reset();
    sendCode(pending);
  }, [pending, confirm, sendCode]);

  if (pending) {
    return (
      <Stack spacing={2}>
        <Typography variant="body2" color="text.secondary">
          We sent a 6-digit code to{' '}
          <Box component="span" sx={{ color: 'text.primary', fontWeight: 700 }}>
            {pending.email}
          </Box>
          . Enter it to save the address.
        </Typography>
        <EmailCodeStep
          idPrefix="email-change"
          onVerify={handleVerify}
          onResend={handleResend}
          isVerifying={confirm.isPending}
          isResending={request.isPending}
          resendSecondsLeft={resendTimer.secondsLeft}
          verifyError={confirm.isError ? confirm.error : null}
          resendError={request.isError ? request.error : null}
          submitLabel="Verify email"
        />
        <Button variant="text" onClick={onCancel} disabled={confirm.isPending}>
          Cancel
        </Button>
      </Stack>
    );
  }

  return (
    <EmailAddressStep
      account={account}
      onSubmit={sendCode}
      onCancel={onCancel}
      isSubmitting={request.isPending}
      error={request.isError ? request.error : null}
    />
  );
}

/** Step 1: the new address, and the current password when there is one. */
function EmailAddressStep({
  account,
  onSubmit,
  onCancel,
  isSubmitting,
  error,
}: {
  account: AccountCredentials;
  onSubmit: (payload: EmailChangePayload) => void;
  onCancel: () => void;
  isSubmitting: boolean;
  error: ApiError | null;
}) {
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors },
  } = useForm<EmailChangeFormValues>({
    resolver: zodResolver(emailChangeSchema(account.hasPassword)),
    defaultValues: { email: '', currentPassword: '' },
    mode: 'onBlur',
  });

  // Refusals about one field are shown on that field.
  useEffect(() => {
    if (error?.code === AUTH_ERROR.CURRENT_PASSWORD_INCORRECT) {
      setError('currentPassword', { message: error.message }, { shouldFocus: true });
    } else if (error?.code === AUTH_ERROR.EMAIL_IN_USE) {
      setError('email', { message: error.message }, { shouldFocus: true });
    }
  }, [error, setError]);

  const fieldCodes: string[] = [AUTH_ERROR.CURRENT_PASSWORD_INCORRECT, AUTH_ERROR.EMAIL_IN_USE];
  const formError = error && !fieldCodes.includes(error.code) ? error : null;

  const submit = handleSubmit(({ email, currentPassword }) =>
    onSubmit({ email, ...(account.hasPassword ? { currentPassword } : {}) }),
  );

  return (
    <Stack component="form" spacing={2} onSubmit={(event) => void submit(event)} noValidate>
      {formError ? (
        <Alert severity={emailAuthErrorSeverity(formError)} role="alert">
          {emailAuthErrorMessage(formError)}
        </Alert>
      ) : null}

      <LabelledTextField
        id="profile-new-email"
        label={account.email ? 'New email' : 'Email'}
        type="email"
        autoComplete="email"
        placeholder="you@example.com"
        inputProps={{ inputMode: 'email', autoCapitalize: 'none', spellCheck: false }}
        error={Boolean(errors.email)}
        helperText={errors.email?.message ?? 'We’ll send a code to this address to confirm it.'}
        {...register('email')}
      />
      {account.hasPassword ? (
        <PasswordField
          id="profile-email-current-password"
          label="Current password"
          autoComplete="current-password"
          error={Boolean(errors.currentPassword)}
          helperText={errors.currentPassword?.message}
          {...register('currentPassword')}
        />
      ) : null}

      <Stack direction="row" spacing={1.5} justifyContent="flex-end">
        <Button variant="text" onClick={onCancel} disabled={isSubmitting}>
          Cancel
        </Button>
        <Button type="submit" variant="contained" disabled={isSubmitting}>
          {isSubmitting ? 'Sending code…' : 'Send code'}
        </Button>
      </Stack>
    </Stack>
  );
}
