import { useEffect, useMemo, useState } from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import {
  Alert,
  Box,
  Button,
  Card,
  CardContent,
  Chip,
  Collapse,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import KeyRoundedIcon from '@mui/icons-material/KeyRounded';
import VerifiedRoundedIcon from '@mui/icons-material/VerifiedRounded';
import { QueryStateView } from '@components/QueryStateView';
import type { AccountCredentials, EmailChangePayload } from '@features/auth/api';
import { CodeField } from '@features/auth/CodeField';
import { DevMailNotice } from '@features/auth/DevMailNotice';
import { EMAIL_AUTH_ERRORS, RESEND_COOLDOWN_SECONDS } from '@features/auth/constants';
import {
  useAccountCredentials,
  useChangePassword,
  useConfirmEmailChange,
  useRequestEmailChange,
} from '@features/auth/hooks';
import { PasswordField } from '@features/auth/PasswordField';
import { ResendCodeRow } from '@features/auth/ResendCodeRow';
import {
  buildEmailChangeSchema,
  buildPasswordChangeSchema,
  emailCodeSchema,
  PASSWORD_HINT,
  type EmailChangeFormValues,
  type EmailCodeFormValues,
  type PasswordChangeFormValues,
} from '@features/auth/schemas';
import { useCountdown } from '@features/auth/useCountdown';

/** Which form, if any, is open. */
type Mode = 'view' | 'email' | 'emailCode' | 'password';

/**
 * The account's email and password (email-auth Requirement 4): add or change
 * the email, confirmed by a code sent to the new address, and set or change
 * the password. A provider who signed up by mobile OTP adds both here to be
 * able to sign in by email as well.
 *
 * The current password is asked for only when the account has one, and a
 * password needs a verified email first (400 EMAIL_REQUIRED), so the password
 * form points there when there is none.
 */
export function EmailPasswordCard() {
  const credentials = useAccountCredentials();
  const [mode, setMode] = useState<Mode>('view');
  const [notice, setNotice] = useState<string | null>(null);
  // The email change awaiting its code, kept to re-send the code.
  const [pendingChange, setPendingChange] = useState<EmailChangePayload | null>(null);

  const open = (next: Mode) => {
    setNotice(null);
    setMode(next);
  };

  const finish = (message: string) => {
    setPendingChange(null);
    setMode('view');
    setNotice(message);
  };

  const account = credentials.data;

  return (
    <Card>
      <CardContent>
        <Stack direction="row" spacing={1} alignItems="center" sx={{ mb: 1.5 }}>
          <KeyRoundedIcon color="primary" aria-hidden />
          <Typography variant="subtitle1" fontWeight={700} component="h2">
            Email &amp; password
          </Typography>
        </Stack>

        <QueryStateView
          isLoading={credentials.isLoading}
          isError={credentials.isError}
          error={credentials.error}
          onRetry={() => void credentials.refetch()}
        >
          {account ? (
            <>
              <CredentialsSummary account={account} />

              {notice ? (
                <Alert severity="success" sx={{ mt: 1.5 }} onClose={() => setNotice(null)}>
                  {notice}
                </Alert>
              ) : null}

              <Collapse in={mode === 'email'} unmountOnExit>
                <EmailChangeForm
                  account={account}
                  onCancel={() => setMode('view')}
                  onCodeSent={(payload) => {
                    setPendingChange(payload);
                    setMode('emailCode');
                  }}
                />
              </Collapse>

              <Collapse in={mode === 'emailCode' && pendingChange !== null} unmountOnExit>
                {pendingChange ? (
                  <EmailCodeForm
                    pendingChange={pendingChange}
                    onCancel={() => {
                      setPendingChange(null);
                      setMode('view');
                    }}
                    onVerified={() => finish('Email verified and saved.')}
                  />
                ) : null}
              </Collapse>

              <Collapse in={mode === 'password'} unmountOnExit>
                <PasswordChangeForm
                  account={account}
                  onCancel={() => setMode('view')}
                  onAddEmail={() => open('email')}
                  onSaved={() =>
                    finish(
                      account.hasPassword
                        ? 'Password changed.'
                        : 'Password set. You can now sign in with your email and password.',
                    )
                  }
                />
              </Collapse>

              {mode === 'view' ? (
                <Stack direction="row" spacing={1} sx={{ mt: 2 }}>
                  <Button variant="outlined" fullWidth onClick={() => open('email')}>
                    {account.email ? 'Change email' : 'Add email'}
                  </Button>
                  <Button variant="outlined" fullWidth onClick={() => open('password')}>
                    {account.hasPassword ? 'Change password' : 'Set password'}
                  </Button>
                </Stack>
              ) : null}
            </>
          ) : null}
        </QueryStateView>
      </CardContent>
    </Card>
  );
}

/** The email on file (or none) and whether a password is set. */
function CredentialsSummary({ account }: { account: AccountCredentials }) {
  return (
    <Stack spacing={1.25}>
      <Stack
        direction="row"
        justifyContent="space-between"
        alignItems="center"
        spacing={1}
        flexWrap="wrap"
        useFlexGap
      >
        <Box sx={{ minWidth: 0 }}>
          <Typography variant="caption" color="text.secondary">
            Email
          </Typography>
          <Typography variant="body1" fontWeight={600} noWrap>
            {account.email ?? 'Not added'}
          </Typography>
        </Box>
        {account.email && account.emailVerified ? (
          <Chip
            size="small"
            color="success"
            icon={<VerifiedRoundedIcon sx={{ fontSize: 16 }} />}
            label="Verified"
          />
        ) : null}
      </Stack>
      <Box>
        <Typography variant="caption" color="text.secondary">
          Password
        </Typography>
        <Typography variant="body1" fontWeight={600}>
          {account.hasPassword ? 'Set' : 'Not set'}
        </Typography>
      </Box>
      {account.email && account.hasPassword ? null : (
        <Typography variant="body2" color="text.secondary">
          Add an email and a password to sign in with them as well as with your mobile number.
        </Typography>
      )}
    </Stack>
  );
}

interface EmailChangeFormProps {
  account: AccountCredentials;
  onCancel: () => void;
  onCodeSent: (payload: EmailChangePayload) => void;
}

/** Step 1 of adding or changing the email: the new address (and the current password, if any). */
function EmailChangeForm({ account, onCancel, onCodeSent }: EmailChangeFormProps) {
  const request = useRequestEmailChange();
  const schema = useMemo(() => buildEmailChangeSchema(account.hasPassword), [account.hasPassword]);

  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<EmailChangeFormValues>({
    resolver: zodResolver(schema),
    defaultValues: { email: '', currentPassword: '' },
    mode: 'onSubmit',
  });

  const onSubmit = ({ email, currentPassword }: EmailChangeFormValues) => {
    const payload: EmailChangePayload = account.hasPassword
      ? { email, currentPassword }
      : { email };
    request.mutate(payload, { onSuccess: () => onCodeSent(payload) });
  };

  return (
    <Stack
      component="form"
      spacing={1.5}
      sx={{ mt: 2 }}
      noValidate
      onSubmit={(event) => void handleSubmit(onSubmit)(event)}
    >
      <TextField
        label={account.email ? 'New email' : 'Email'}
        type="email"
        fullWidth
        size="small"
        autoComplete="email"
        inputProps={{ inputMode: 'email' }}
        error={Boolean(errors.email)}
        helperText={errors.email?.message ?? 'We will email a 6-digit code to this address.'}
        {...register('email')}
      />
      {account.hasPassword ? (
        <PasswordField
          label="Current password"
          size="small"
          autoComplete="current-password"
          error={Boolean(errors.currentPassword)}
          helperText={errors.currentPassword?.message ?? 'To confirm it is you.'}
          {...register('currentPassword')}
        />
      ) : null}

      {request.error ? <Alert severity="error">{request.error.message}</Alert> : null}

      <Stack direction="row" spacing={1}>
        <Button
          type="button"
          variant="outlined"
          fullWidth
          onClick={onCancel}
          disabled={request.isPending}
        >
          Cancel
        </Button>
        <Button type="submit" variant="contained" fullWidth disabled={request.isPending}>
          {request.isPending ? 'Sending…' : 'Send code'}
        </Button>
      </Stack>
    </Stack>
  );
}

interface EmailCodeFormProps {
  pendingChange: EmailChangePayload;
  onCancel: () => void;
  onVerified: () => void;
}

/** Step 2: the code sent to the new address. The email is saved only once it is entered. */
function EmailCodeForm({ pendingChange, onCancel, onVerified }: EmailCodeFormProps) {
  const confirm = useConfirmEmailChange();
  const resend = useRequestEmailChange();
  const cooldown = useCountdown(0);

  // The code went out just before this form opened: start the resend cooldown.
  const cooldownReset = cooldown.reset;
  useEffect(() => {
    cooldownReset(RESEND_COOLDOWN_SECONDS);
  }, [cooldownReset]);

  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<EmailCodeFormValues>({
    resolver: zodResolver(emailCodeSchema),
    defaultValues: { code: '' },
    mode: 'onSubmit',
  });

  const handleResend = () => {
    confirm.reset();
    resend.mutate(pendingChange, {
      onSuccess: () => cooldown.reset(RESEND_COOLDOWN_SECONDS),
      onError: (error) => {
        if (error.retryAfterSeconds !== undefined) cooldown.reset(error.retryAfterSeconds);
      },
    });
  };

  const codeExpired = confirm.error?.code === EMAIL_AUTH_ERRORS.codeExpired;

  return (
    <Stack
      component="form"
      spacing={1.5}
      sx={{ mt: 2 }}
      noValidate
      onSubmit={(event) =>
        void handleSubmit(({ code }) => confirm.mutate({ code }, { onSuccess: onVerified }))(event)
      }
    >
      <Typography variant="body2" color="text.secondary">
        Enter the 6-digit code we sent to <strong>{pendingChange.email}</strong>.
      </Typography>

      {confirm.error ? (
        <Alert
          severity="error"
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
          {confirm.error.message}
        </Alert>
      ) : null}
      {resend.error ? <Alert severity="error">{resend.error.message}</Alert> : null}
      {resend.isSuccess && !confirm.error ? (
        <Alert severity="success">We sent a new code.</Alert>
      ) : null}

      <CodeField
        {...register('code')}
        error={Boolean(errors.code)}
        helperText={errors.code?.message ?? ' '}
        disabled={confirm.isPending}
      />

      <DevMailNotice />

      <ResendCodeRow
        onResend={handleResend}
        secondsLeft={cooldown.secondsLeft}
        isResending={resend.isPending}
        disabled={confirm.isPending}
      />

      <Stack direction="row" spacing={1}>
        <Button
          type="button"
          variant="outlined"
          fullWidth
          onClick={onCancel}
          disabled={confirm.isPending}
        >
          Cancel
        </Button>
        <Button type="submit" variant="contained" fullWidth disabled={confirm.isPending}>
          {confirm.isPending ? 'Verifying…' : 'Verify email'}
        </Button>
      </Stack>
    </Stack>
  );
}

interface PasswordChangeFormProps {
  account: AccountCredentials;
  onCancel: () => void;
  onAddEmail: () => void;
  onSaved: () => void;
}

/** Set a first password, or change it (confirming the current one). */
function PasswordChangeForm({ account, onCancel, onAddEmail, onSaved }: PasswordChangeFormProps) {
  const save = useChangePassword();
  const schema = useMemo(
    () => buildPasswordChangeSchema(account.hasPassword),
    [account.hasPassword],
  );

  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<PasswordChangeFormValues>({
    resolver: zodResolver(schema),
    defaultValues: { currentPassword: '', newPassword: '', confirmPassword: '' },
    mode: 'onSubmit',
  });

  const onSubmit = ({ currentPassword, newPassword }: PasswordChangeFormValues) => {
    save.mutate(account.hasPassword ? { currentPassword, newPassword } : { newPassword }, {
      onSuccess: onSaved,
    });
  };

  // You sign in with email + password, so a password needs a verified email.
  if (!account.email || save.error?.code === EMAIL_AUTH_ERRORS.emailRequired) {
    return (
      <Stack spacing={1.5} sx={{ mt: 2 }}>
        <Alert
          severity="info"
          action={
            <Button color="inherit" size="small" onClick={onAddEmail}>
              Add email
            </Button>
          }
        >
          Add and verify an email first. You will sign in with it and your password.
        </Alert>
        <Button type="button" variant="outlined" fullWidth onClick={onCancel}>
          Cancel
        </Button>
      </Stack>
    );
  }

  return (
    <Stack
      component="form"
      spacing={1.5}
      sx={{ mt: 2 }}
      noValidate
      onSubmit={(event) => void handleSubmit(onSubmit)(event)}
    >
      {account.hasPassword ? (
        <PasswordField
          label="Current password"
          size="small"
          autoComplete="current-password"
          error={Boolean(errors.currentPassword)}
          helperText={errors.currentPassword?.message}
          {...register('currentPassword')}
        />
      ) : null}
      <PasswordField
        label="New password"
        size="small"
        autoComplete="new-password"
        error={Boolean(errors.newPassword)}
        helperText={errors.newPassword?.message ?? PASSWORD_HINT}
        {...register('newPassword')}
      />
      <PasswordField
        label="Confirm new password"
        size="small"
        autoComplete="new-password"
        error={Boolean(errors.confirmPassword)}
        helperText={errors.confirmPassword?.message}
        {...register('confirmPassword')}
      />

      {save.error ? <Alert severity="error">{save.error.message}</Alert> : null}

      <Stack direction="row" spacing={1}>
        <Button
          type="button"
          variant="outlined"
          fullWidth
          onClick={onCancel}
          disabled={save.isPending}
        >
          Cancel
        </Button>
        <Button type="submit" variant="contained" fullWidth disabled={save.isPending}>
          {save.isPending ? 'Saving…' : account.hasPassword ? 'Change password' : 'Set password'}
        </Button>
      </Stack>
    </Stack>
  );
}
