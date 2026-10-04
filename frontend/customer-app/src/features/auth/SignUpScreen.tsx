import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { useNavigate } from 'react-router-dom';
import { Alert, AlertTitle, Button, Link, Stack, Typography } from '@mui/material';
import ArrowBackRoundedIcon from '@mui/icons-material/ArrowBackRounded';
import ArrowForwardRoundedIcon from '@mui/icons-material/ArrowForwardRounded';
import { AUTH_ERROR } from './constants';
import { emailAuthErrorMessage, emailAuthErrorSeverity } from './emailErrors';
import { toE164 } from './phone';
import { useEmailSignUp } from './hooks';
import { useSignedInRedirect } from './useSignedInRedirect';
import { AuthLayout } from './AuthLayout';
import { LabelledTextField, MobileNumberField, PasswordField } from './AuthFields';
import { PASSWORD_HINT, signUpSchema, type SignUpFormValues } from './schemas';

/**
 * Email sign-up (email-auth Requirement 1): name, email, mobile number and a
 * password, then the emailed code on the next screen.
 *
 * The Auth Service answers 202 whether or not the address already has an
 * account (that address is emailed a notice instead of a code), so the code
 * screen opens either way. A mobile number that already has an account is the
 * one refusal worth acting on: that person signs in by OTP and adds an email
 * in their profile.
 */
export function SignUpScreen() {
  const navigate = useNavigate();
  const { forward } = useSignedInRedirect();
  const signUp = useEmailSignUp();

  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<SignUpFormValues>({
    resolver: zodResolver(signUpSchema),
    defaultValues: {
      displayName: '',
      email: '',
      mobileNumber: '',
      password: '',
      confirmPassword: '',
    },
    mode: 'onBlur',
  });

  const submit = handleSubmit(({ displayName, email, mobileNumber, password }) => {
    signUp.mutate(
      { displayName, email, mobileNumber: toE164(mobileNumber), password },
      {
        onSuccess: (data) => {
          navigate('/signup/verify', {
            state: { ...forward, email, expiresInSeconds: data.expiresInSeconds },
          });
        },
      },
    );
  });

  const signUpError = signUp.isError ? signUp.error : null;
  const mobileInUse = signUpError?.code === AUTH_ERROR.MOBILE_IN_USE;

  return (
    <AuthLayout>
      <Button
        size="small"
        startIcon={<ArrowBackRoundedIcon />}
        onClick={() => navigate('/login', { state: { ...forward, method: 'email' } })}
        disabled={signUp.isPending}
        sx={{ ml: -1, mb: 1.5, color: 'text.secondary' }}
      >
        Back
      </Button>

      <Typography variant="h3" component="h1">
        Create your account
      </Typography>
      <Typography variant="body1" color="text.secondary" sx={{ mt: 1, mb: 3 }}>
        Sign up with your email. We’ll send a 6-digit code to confirm it.
      </Typography>

      <Stack component="form" spacing={2.5} onSubmit={(event) => void submit(event)} noValidate>
        {mobileInUse ? (
          <Alert
            severity="info"
            role="alert"
            action={
              <Button
                color="inherit"
                size="small"
                onClick={() => navigate('/login', { state: { ...forward, method: 'mobile' } })}
                sx={{ whiteSpace: 'nowrap' }}
              >
                Sign in with OTP
              </Button>
            }
          >
            <AlertTitle>You already have an account</AlertTitle>
            {signUpError?.message}
          </Alert>
        ) : signUpError ? (
          <Alert severity={emailAuthErrorSeverity(signUpError)} role="alert">
            {emailAuthErrorMessage(signUpError)}
          </Alert>
        ) : null}

        <LabelledTextField
          id="signup-name"
          label="Full name"
          autoComplete="name"
          placeholder="Your name"
          error={Boolean(errors.displayName)}
          helperText={errors.displayName?.message}
          {...register('displayName')}
        />
        <LabelledTextField
          id="signup-email"
          label="Email"
          type="email"
          autoComplete="email"
          placeholder="you@example.com"
          inputProps={{ inputMode: 'email', autoCapitalize: 'none', spellCheck: false }}
          error={Boolean(errors.email)}
          helperText={errors.email?.message}
          {...register('email')}
        />
        <MobileNumberField
          id="signup-mobile"
          error={Boolean(errors.mobileNumber) || mobileInUse}
          helperText={errors.mobileNumber?.message ?? 'For booking updates by SMS.'}
          {...register('mobileNumber')}
        />
        <PasswordField
          id="signup-password"
          label="Password"
          autoComplete="new-password"
          error={Boolean(errors.password)}
          helperText={errors.password?.message ?? PASSWORD_HINT}
          {...register('password')}
        />
        <PasswordField
          id="signup-confirm-password"
          label="Confirm password"
          autoComplete="new-password"
          error={Boolean(errors.confirmPassword)}
          helperText={errors.confirmPassword?.message}
          {...register('confirmPassword')}
        />

        <Button
          type="submit"
          variant="contained"
          size="large"
          fullWidth
          disabled={signUp.isPending}
          endIcon={signUp.isPending ? undefined : <ArrowForwardRoundedIcon />}
        >
          {signUp.isPending ? 'Creating account…' : 'Create account'}
        </Button>

        <Typography variant="body2" color="text.secondary" align="center">
          Already have an account?{' '}
          <Link
            component="button"
            type="button"
            variant="body2"
            underline="hover"
            fontWeight={700}
            onClick={() => navigate('/login', { state: { ...forward, method: 'email' } })}
            sx={{ verticalAlign: 'baseline' }}
          >
            Sign in
          </Link>
        </Typography>
      </Stack>
    </AuthLayout>
  );
}
