import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { Link as RouterLink, Navigate } from 'react-router-dom';
import {
  Alert,
  Button,
  Divider,
  InputAdornment,
  Link,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import ArrowForwardRoundedIcon from '@mui/icons-material/ArrowForwardRounded';
import { isApiError } from '@api/client';
import { StandalonePage } from '@components/StandalonePage';
import { EmailCodeStep } from '@features/auth/EmailCodeStep';
import { LabelledField, PasswordInput } from '@features/auth/FormFields';
import { useEmailSignup } from '@features/auth/hooks';
import { toE164 } from '@features/auth/phone';
import { emailSignupSchema, type EmailSignupFormValues } from '@features/auth/schemas';
import { useAuthStore } from '@stores/authStore';
import { AgencySteps } from './AgencySteps';

/** Where an applicant goes once signed in: the agency form or their status. */
const APPLICATION_PATH = '/agency';

/** Sign-up answers that name one field; the rest are shown above the form. */
const FIELD_ERRORS: Record<string, keyof EmailSignupFormValues> = {
  MOBILE_IN_USE: 'mobileNumber',
  WEAK_PASSWORD: 'password',
};

/**
 * "Register your agency", step 1 (email-auth Requirement 5.1): the applicant's
 * own account. A new person signs up with email and password as a CUSTOMER — a
 * plain person account; the agency role comes only with approval — and enters
 * the emailed code, which signs them in. Someone with an account signs in
 * instead. Either way the application page takes over once there is a session.
 *
 * The code screen does not say whether the address was new: the service sends
 * an existing account a "you already have an account" email rather than a code
 * and answers identically (Property EA1), so the copy covers both.
 */
export function RegisterAgencyScreen() {
  const isAuthenticated = useAuthStore((state) => state.isAuthenticated);
  const signup = useEmailSignup();
  const [sent, setSent] = useState<{
    email: string;
    displayName: string;
    expiresInSeconds: number;
  } | null>(null);

  const {
    register,
    handleSubmit,
    setError,
    formState: { errors },
  } = useForm<EmailSignupFormValues>({
    resolver: zodResolver(emailSignupSchema),
    defaultValues: {
      displayName: '',
      email: '',
      mobileNumber: '',
      password: '',
      confirmPassword: '',
    },
    mode: 'onBlur',
  });

  if (isAuthenticated) {
    return <Navigate to={APPLICATION_PATH} replace />;
  }

  const onSubmit = (values: EmailSignupFormValues) => {
    const email = values.email.trim().toLowerCase();
    const displayName = values.displayName.trim();
    signup.mutate(
      {
        displayName,
        email,
        mobileNumber: toE164(values.mobileNumber),
        password: values.password,
        role: 'CUSTOMER',
      },
      {
        onSuccess: (data) =>
          setSent({ email, displayName, expiresInSeconds: data.expiresInSeconds }),
        onError: (error) => {
          const field = FIELD_ERRORS[error.code];
          if (field) setError(field, { message: error.message });
        },
      },
    );
  };

  const signIn = (
    <Typography variant="body2" color="text.secondary">
      Already have a HomeFix account?{' '}
      <Link
        component={RouterLink}
        to="/login"
        state={{ from: { pathname: APPLICATION_PATH } }}
        fontWeight={600}
      >
        Sign in
      </Link>
    </Typography>
  );

  if (sent) {
    return (
      <StandalonePage
        title="Check your email"
        description={`If ${sent.email} is new to HomeFix, we sent it a 6-digit code. If it already has an account, we emailed it how to sign in instead.`}
      >
        <AgencySteps active={0} />
        <EmailCodeStep
          email={sent.email}
          expiresInSeconds={sent.expiresInSeconds}
          displayName={sent.displayName}
          onBack={() => {
            signup.reset();
            setSent(null);
          }}
        />
        <Divider sx={{ my: 2.5 }} />
        {signIn}
      </StandalonePage>
    );
  }

  const formError =
    signup.isError && isApiError(signup.error) && !FIELD_ERRORS[signup.error.code]
      ? signup.error.message
      : null;

  return (
    <StandalonePage
      title="Register your agency"
      description="Create your account first; you will describe your agency next. HomeFix reviews every agency before it receives requests."
    >
      <AgencySteps active={0} />
      <Stack
        component="form"
        spacing={2}
        onSubmit={(event) => void handleSubmit(onSubmit)(event)}
        noValidate
      >
        {formError ? <Alert severity="error">{formError}</Alert> : null}

        <LabelledField label="Your name">
          <TextField
            autoComplete="name"
            fullWidth
            error={Boolean(errors.displayName)}
            helperText={errors.displayName?.message ?? ' '}
            inputProps={{ 'aria-label': 'Your name' }}
            {...register('displayName')}
          />
        </LabelledField>

        <LabelledField label="Email">
          <TextField
            type="email"
            autoComplete="email"
            fullWidth
            placeholder="you@agency.com"
            error={Boolean(errors.email)}
            helperText={errors.email?.message ?? 'You will sign in with this address.'}
            inputProps={{ 'aria-label': 'Email', autoCapitalize: 'none', spellCheck: false }}
            {...register('email')}
          />
        </LabelledField>

        <LabelledField label="Mobile number">
          <TextField
            type="tel"
            autoComplete="tel"
            fullWidth
            placeholder="98765 43210"
            error={Boolean(errors.mobileNumber)}
            helperText={errors.mobileNumber?.message ?? ' '}
            InputProps={{
              startAdornment: (
                <InputAdornment position="start">
                  <Typography
                    variant="body1"
                    fontWeight={600}
                    sx={{ pr: 1.25, mr: 1.25, borderRight: 1, borderColor: 'divider' }}
                  >
                    +91
                  </Typography>
                </InputAdornment>
              ),
            }}
            inputProps={{ inputMode: 'tel', 'aria-label': 'Mobile number' }}
            {...register('mobileNumber')}
          />
        </LabelledField>

        <LabelledField label="Password">
          <PasswordInput
            registration={register('password')}
            ariaLabel="Password"
            autoComplete="new-password"
            error={errors.password?.message}
            helperText="8–72 characters, with a letter and a number."
          />
        </LabelledField>

        <LabelledField label="Confirm password">
          <PasswordInput
            registration={register('confirmPassword')}
            ariaLabel="Confirm password"
            autoComplete="new-password"
            error={errors.confirmPassword?.message}
          />
        </LabelledField>

        <Button
          type="submit"
          variant="contained"
          size="large"
          fullWidth
          disabled={signup.isPending}
          endIcon={signup.isPending ? undefined : <ArrowForwardRoundedIcon />}
        >
          {signup.isPending ? 'Creating account…' : 'Create account'}
        </Button>
      </Stack>
      <Divider sx={{ my: 2.5 }} />
      {signIn}
    </StandalonePage>
  );
}
