import { useEffect } from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { Alert, Button, Stack } from '@mui/material';
import type { ApiError } from '@api/client';
import { AUTH_ERROR } from '@features/auth/constants';
import { emailAuthErrorMessage, emailAuthErrorSeverity } from '@features/auth/emailErrors';
import { PasswordField } from '@features/auth/AuthFields';
import { PASSWORD_HINT } from '@features/auth/schemas';
import { useChangePassword } from './hooks';
import { passwordChangeSchema, type PasswordChangeFormValues } from './schemas';
import type { AccountCredentials } from './api';

interface PasswordChangeFormProps {
  account: AccountCredentials;
  /** Saved; `firstPassword` is true when the account had none before. */
  onDone: (firstPassword: boolean) => void;
  onCancel: () => void;
  /** The service wants a verified email first: open the email form instead. */
  onAddEmail: () => void;
}

/** Codes reported on a field rather than above the form. */
const FIELD_CODES: string[] = [AUTH_ERROR.CURRENT_PASSWORD_INCORRECT, AUTH_ERROR.WEAK_PASSWORD];

/**
 * Set a first password or change the current one (email-auth Requirement
 * 4.1–4.2). The current password is asked for only when the account has one.
 * A password needs something to sign in with, so an account with no verified
 * email is sent to add one first (EMAIL_REQUIRED).
 */
export function PasswordChangeForm({
  account,
  onDone,
  onCancel,
  onAddEmail,
}: PasswordChangeFormProps) {
  const change = useChangePassword();

  const {
    register,
    handleSubmit,
    setError,
    formState: { errors },
  } = useForm<PasswordChangeFormValues>({
    resolver: zodResolver(passwordChangeSchema(account.hasPassword)),
    defaultValues: { currentPassword: '', newPassword: '', confirmPassword: '' },
    mode: 'onBlur',
  });

  const error: ApiError | null = change.isError ? change.error : null;

  useEffect(() => {
    if (error?.code === AUTH_ERROR.CURRENT_PASSWORD_INCORRECT) {
      setError('currentPassword', { message: error.message }, { shouldFocus: true });
    } else if (error?.code === AUTH_ERROR.WEAK_PASSWORD) {
      setError('newPassword', { message: error.message }, { shouldFocus: true });
    }
  }, [error, setError]);

  const submit = handleSubmit(({ currentPassword, newPassword }) =>
    change.mutate(
      { newPassword, ...(account.hasPassword ? { currentPassword } : {}) },
      { onSuccess: () => onDone(!account.hasPassword) },
    ),
  );

  return (
    <Stack component="form" spacing={2} onSubmit={(event) => void submit(event)} noValidate>
      {error?.code === AUTH_ERROR.EMAIL_REQUIRED ? (
        <Alert
          severity="info"
          role="alert"
          action={
            <Button color="inherit" size="small" onClick={onAddEmail}>
              Add email
            </Button>
          }
        >
          {error.message}
        </Alert>
      ) : error && !FIELD_CODES.includes(error.code) ? (
        <Alert severity={emailAuthErrorSeverity(error)} role="alert">
          {emailAuthErrorMessage(error)}
        </Alert>
      ) : null}

      {account.hasPassword ? (
        <PasswordField
          id="profile-current-password"
          label="Current password"
          autoComplete="current-password"
          error={Boolean(errors.currentPassword)}
          helperText={errors.currentPassword?.message}
          {...register('currentPassword')}
        />
      ) : null}
      <PasswordField
        id="profile-new-password"
        label={account.hasPassword ? 'New password' : 'Password'}
        autoComplete="new-password"
        error={Boolean(errors.newPassword)}
        helperText={errors.newPassword?.message ?? PASSWORD_HINT}
        {...register('newPassword')}
      />
      <PasswordField
        id="profile-confirm-password"
        label="Confirm password"
        autoComplete="new-password"
        error={Boolean(errors.confirmPassword)}
        helperText={errors.confirmPassword?.message}
        {...register('confirmPassword')}
      />

      <Stack direction="row" spacing={1.5} justifyContent="flex-end">
        <Button variant="text" onClick={onCancel} disabled={change.isPending}>
          Cancel
        </Button>
        <Button type="submit" variant="contained" disabled={change.isPending}>
          {change.isPending ? 'Saving…' : account.hasPassword ? 'Change password' : 'Set password'}
        </Button>
      </Stack>
    </Stack>
  );
}
