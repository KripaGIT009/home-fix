import type { ReactNode } from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { Alert, Button, InputAdornment, Link, Stack, TextField } from '@mui/material';
import LoginRoundedIcon from '@mui/icons-material/LoginRounded';
import PersonOutlineRoundedIcon from '@mui/icons-material/PersonOutlineRounded';
import { LabelledField, PasswordInput } from './FormFields';
import { credentialsSchema, type CredentialsFormValues } from './schemas';

interface PasswordStepProps {
  onSubmit: (values: CredentialsFormValues) => void;
  isSubmitting: boolean;
  errorMessage: string | null;
  /**
   * Shown inside the error, e.g. "Resend code" for an email sign-up whose code
   * was never entered (email-auth Requirement 2.3). Turns the error into a
   * warning: the password was right, there is one step left.
   */
  errorAction?: ReactNode;
  /** Opens the password reset flow (email-auth Requirement 3). */
  onForgotPassword: () => void;
}

/**
 * Email-or-username and password sign-in (email-auth Requirement 2.5): staff
 * use their console username or email, agency applicants their email.
 *
 * A single submit, unlike the OTP flow's two steps. The error surface is
 * deliberately flat: the service answers a wrong identifier and a wrong
 * password with the same code, and this form shows that one message rather than
 * guessing which field was at fault, so the console does not become a way to
 * discover which accounts exist.
 */
export function PasswordStep({
  onSubmit,
  isSubmitting,
  errorMessage,
  errorAction,
  onForgotPassword,
}: PasswordStepProps) {
  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<CredentialsFormValues>({
    resolver: zodResolver(credentialsSchema),
    defaultValues: { identifier: '', password: '' },
    mode: 'onBlur',
  });

  return (
    <Stack
      component="form"
      spacing={2.25}
      onSubmit={(event) => void handleSubmit(onSubmit)(event)}
      noValidate
    >
      {errorMessage ? (
        <Alert severity={errorAction ? 'warning' : 'error'} action={errorAction}>
          {errorMessage}
        </Alert>
      ) : null}

      <LabelledField label="Email or username">
        <TextField
          autoComplete="username"
          fullWidth
          placeholder="you@agency.com"
          error={Boolean(errors.identifier)}
          helperText={errors.identifier?.message ?? ' '}
          InputProps={{
            startAdornment: (
              <InputAdornment position="start">
                <PersonOutlineRoundedIcon fontSize="small" sx={{ color: 'text.secondary' }} />
              </InputAdornment>
            ),
          }}
          inputProps={{
            'aria-label': 'Email or username',
            autoCapitalize: 'none',
            spellCheck: false,
          }}
          {...register('identifier')}
        />
      </LabelledField>

      <LabelledField
        label="Password"
        action={
          <Link component="button" type="button" variant="body2" onClick={onForgotPassword}>
            Forgot password?
          </Link>
        }
      >
        <PasswordInput
          registration={register('password')}
          ariaLabel="Password"
          autoComplete="current-password"
          error={errors.password?.message}
        />
      </LabelledField>

      <Button
        type="submit"
        variant="contained"
        size="large"
        fullWidth
        disabled={isSubmitting}
        endIcon={isSubmitting ? undefined : <LoginRoundedIcon />}
      >
        {isSubmitting ? 'Signing in…' : 'Sign in'}
      </Button>
    </Stack>
  );
}
