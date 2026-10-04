import { useEffect } from 'react';
import { Controller, useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { Alert, Box, Button, Stack, Typography } from '@mui/material';
import type { ApiError } from '@api/client';
import { AUTH_ERROR, OTP_LENGTH } from './constants';
import { emailAuthErrorMessage, emailAuthErrorSeverity } from './emailErrors';
import { formatDuration } from './lockout';
import { OtpInput } from './OtpInput';
import { DevMailHint } from './DevMailHint';
import { emailCodeSchema, type EmailCodeFormValues } from './schemas';

interface EmailCodeStepProps {
  onVerify: (code: string) => void;
  onResend: () => void;
  isVerifying: boolean;
  /** True while a resend request is in flight. */
  isResending: boolean;
  /** Seconds before another code may be requested (one a minute). */
  resendSecondsLeft: number;
  /** The rejected verification, if the last attempt failed. */
  verifyError: ApiError | null;
  /** The rejected resend, if the last one failed. */
  resendError: ApiError | null;
  /** Label of the submit button, e.g. "Verify & continue". */
  submitLabel: string;
  /** Element id prefix, so two code steps never share ids. */
  idPrefix: string;
}

/**
 * Enter a 6-digit code sent by email (email-auth Requirements 1.6, 1.8, 4.1).
 *
 * The code is submitted as soon as the last digit is entered. A wrong code
 * shows the attempts the Auth Service says are left and clears the boxes; an
 * expired or used-up code asks for a new one. Resend unlocks once a minute,
 * the limit the service enforces.
 */
export function EmailCodeStep({
  onVerify,
  onResend,
  isVerifying,
  isResending,
  resendSecondsLeft,
  verifyError,
  resendError,
  submitLabel,
  idPrefix,
}: EmailCodeStepProps) {
  const {
    control,
    handleSubmit,
    setValue,
    formState: { errors },
  } = useForm<EmailCodeFormValues>({
    resolver: zodResolver(emailCodeSchema),
    defaultValues: { code: '' },
    mode: 'onSubmit',
  });

  // A rejected code is cleared so the next attempt starts from empty boxes.
  useEffect(() => {
    if (verifyError) setValue('code', '');
  }, [verifyError, setValue]);

  const isExpired = verifyError?.code === AUTH_ERROR.CODE_EXPIRED;
  const isRejectedCode = verifyError?.code === AUTH_ERROR.INVALID_CODE || isExpired;
  const canResend = resendSecondsLeft <= 0 && !isResending && !isVerifying;

  const errorId = `${idPrefix}-code-error`;
  const fieldError =
    errors.code?.message ?? (isRejectedCode && verifyError ? verifyError.message : null);
  // Anything else (a disabled account, an outage) is not about the digits typed.
  const formError = verifyError && !isRejectedCode ? verifyError : null;

  const submit = handleSubmit(({ code }) => onVerify(code));

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
        <Controller
          control={control}
          name="code"
          render={({ field }) => (
            <OtpInput
              length={OTP_LENGTH}
              value={field.value}
              onChange={field.onChange}
              onBlur={field.onBlur}
              onComplete={() => {
                if (!isVerifying) void submit();
              }}
              disabled={isVerifying}
              error={Boolean(fieldError)}
              focusOnMount
              {...(fieldError ? { describedBy: errorId } : {})}
            />
          )}
        />
        {fieldError ? (
          <Typography
            id={errorId}
            role="alert"
            variant="body2"
            color="error.main"
            fontWeight={600}
            sx={{ mt: 1 }}
          >
            {fieldError}
          </Typography>
        ) : null}
      </Box>

      <Stack
        direction="row"
        justifyContent="space-between"
        alignItems="center"
        sx={{ minHeight: 36 }}
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

      <Button type="submit" variant="contained" size="large" fullWidth disabled={isVerifying}>
        {isVerifying ? 'Verifying…' : submitLabel}
      </Button>

      <DevMailHint />
    </Stack>
  );
}
