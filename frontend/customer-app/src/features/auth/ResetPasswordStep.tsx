import { useEffect } from 'react';
import { Controller, useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { Alert, Box, Button, Stack, Typography } from '@mui/material';
import type { ApiError } from '@api/client';
import { AUTH_ERROR, OTP_LENGTH } from './constants';
import { emailAuthErrorMessage, emailAuthErrorSeverity } from './emailErrors';
import { formatDuration } from './lockout';
import { OtpInput } from './OtpInput';
import { PasswordField } from './AuthFields';
import { DevMailHint } from './DevMailHint';
import { PASSWORD_HINT, resetPasswordSchema, type ResetPasswordFormValues } from './schemas';

interface ResetPasswordStepProps {
  onSubmit: (values: { code: string; newPassword: string }) => void;
  onResend: () => void;
  onChangeEmail: () => void;
  isSubmitting: boolean;
  isResending: boolean;
  /** Seconds before another code may be requested (one a minute). */
  resendSecondsLeft: number;
  /** The rejected reset, if the last attempt failed. */
  submitError: ApiError | null;
  /** The rejected resend, if the last one failed. */
  resendError: ApiError | null;
}

const CODE_ERROR_ID = 'reset-code-error';

/**
 * Step 2 of a password reset (email-auth Requirement 3.2): the emailed code
 * and the new password, typed twice. Unlike sign-up the code is not submitted
 * on its own — the password fields still follow it.
 *
 * A wrong or expired code is reported under the boxes (and cleared); a
 * password the service rejects is reported under the password field.
 */
export function ResetPasswordStep({
  onSubmit,
  onResend,
  onChangeEmail,
  isSubmitting,
  isResending,
  resendSecondsLeft,
  submitError,
  resendError,
}: ResetPasswordStepProps) {
  const {
    control,
    register,
    handleSubmit,
    setValue,
    setError,
    formState: { errors },
  } = useForm<ResetPasswordFormValues>({
    resolver: zodResolver(resetPasswordSchema),
    defaultValues: { code: '', newPassword: '', confirmPassword: '' },
    mode: 'onBlur',
  });

  const errorCode = submitError?.code;
  const isCodeError =
    errorCode === AUTH_ERROR.INVALID_CODE || errorCode === AUTH_ERROR.CODE_EXPIRED;
  const isExpired = errorCode === AUTH_ERROR.CODE_EXPIRED;

  useEffect(() => {
    if (!submitError) return;
    if (isCodeError) {
      setValue('code', '');
    } else if (submitError.code === AUTH_ERROR.WEAK_PASSWORD) {
      setError('newPassword', { message: submitError.message }, { shouldFocus: true });
    }
  }, [submitError, isCodeError, setValue, setError]);

  const codeError =
    errors.code?.message ?? (isCodeError && submitError ? submitError.message : null);
  const formError =
    submitError && !isCodeError && submitError.code !== AUTH_ERROR.WEAK_PASSWORD
      ? submitError
      : null;
  const canResend = resendSecondsLeft <= 0 && !isResending && !isSubmitting;

  const submit = handleSubmit(({ code, newPassword }) => onSubmit({ code, newPassword }));

  const handleResend = () => {
    setValue('code', '');
    onResend();
  };

  return (
    <Stack component="form" spacing={2.5} onSubmit={(event) => void submit(event)} noValidate>
      {formError ? (
        <Alert severity={emailAuthErrorSeverity(formError)} role="alert">
          {emailAuthErrorMessage(formError)}
        </Alert>
      ) : null}
      {resendError ? (
        <Alert severity={emailAuthErrorSeverity(resendError)} role="alert">
          {emailAuthErrorMessage(resendError)}
        </Alert>
      ) : null}

      <Box>
        <Typography variant="subtitle2" sx={{ mb: 1, color: 'text.primary' }}>
          Code from the email
        </Typography>
        <Controller
          control={control}
          name="code"
          render={({ field }) => (
            <OtpInput
              length={OTP_LENGTH}
              value={field.value}
              onChange={field.onChange}
              onBlur={field.onBlur}
              disabled={isSubmitting}
              error={Boolean(codeError)}
              focusOnMount
              {...(codeError ? { describedBy: CODE_ERROR_ID } : {})}
            />
          )}
        />
        {codeError ? (
          <Typography
            id={CODE_ERROR_ID}
            role="alert"
            variant="body2"
            color="error.main"
            fontWeight={600}
            sx={{ mt: 1 }}
          >
            {codeError}
          </Typography>
        ) : null}
        <Stack
          direction="row"
          justifyContent="space-between"
          alignItems="center"
          sx={{ mt: 1, minHeight: 36 }}
        >
          <Typography variant="body2" color="text.secondary" fontWeight={600} aria-live="polite">
            {resendSecondsLeft > 0
              ? `New code available in ${formatDuration(resendSecondsLeft)}`
              : 'Didn’t get it? Check your spam folder.'}
          </Typography>
          <Button
            type="button"
            size="small"
            variant={isExpired && canResend ? 'contained' : 'text'}
            onClick={handleResend}
            disabled={!canResend}
          >
            {isResending ? 'Sending…' : 'Resend code'}
          </Button>
        </Stack>
      </Box>

      <PasswordField
        id="reset-new-password"
        label="New password"
        autoComplete="new-password"
        error={Boolean(errors.newPassword)}
        helperText={errors.newPassword?.message ?? PASSWORD_HINT}
        {...register('newPassword')}
      />
      <PasswordField
        id="reset-confirm-password"
        label="Confirm new password"
        autoComplete="new-password"
        error={Boolean(errors.confirmPassword)}
        helperText={errors.confirmPassword?.message}
        {...register('confirmPassword')}
      />

      <Button type="submit" variant="contained" size="large" fullWidth disabled={isSubmitting}>
        {isSubmitting ? 'Saving…' : 'Set new password'}
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

      <DevMailHint />
    </Stack>
  );
}
