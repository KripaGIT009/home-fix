import { Link as RouterLink, Navigate, useNavigate } from 'react-router-dom';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import {
  Alert,
  Box,
  Button,
  InputAdornment,
  Link,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import ArrowForwardRoundedIcon from '@mui/icons-material/ArrowForwardRounded';
import { useAuthStore } from '@stores/authStore';
import { AuthLayout } from './AuthLayout';
import { EMAIL_AUTH_ERRORS, RESEND_COOLDOWN_SECONDS } from './constants';
import { useEmailSignup } from './hooks';
import { PasswordField } from './PasswordField';
import {
  AUTH_ROUTES,
  DEFAULT_SIGNED_IN_ROUTE,
  type EmailCodeLocationState,
  type LoginLocationState,
} from './navigation';
import {
  PASSWORD_HINT,
  signUpSchema,
  toEmailSignupPayload,
  type SignUpFormValues,
} from './schemas';

const EMPTY_FORM: SignUpFormValues = {
  displayName: '',
  email: '',
  mobileNumber: '',
  password: '',
  confirmPassword: '',
};

/**
 * Email sign-up for providers (email-auth Requirement 1): name, email, mobile
 * number and a password typed twice. The Auth Service answers 202 and emails
 * a 6-digit code, which the code screen takes next. The account is created
 * with the SERVICE_PROVIDER role.
 *
 * A mobile number that already has an account is refused (409 MOBILE_IN_USE):
 * that person signs in by OTP and adds an email in their profile instead.
 */
export function SignUpScreen() {
  const navigate = useNavigate();
  const isAuthenticated = useAuthStore((state) => state.isAuthenticated);
  const signup = useEmailSignup();

  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<SignUpFormValues>({
    resolver: zodResolver(signUpSchema),
    defaultValues: EMPTY_FORM,
    mode: 'onBlur',
  });

  if (isAuthenticated) {
    return <Navigate to={DEFAULT_SIGNED_IN_ROUTE} replace />;
  }

  const onSubmit = (values: SignUpFormValues) => {
    const payload = toEmailSignupPayload(values);
    signup.mutate(payload, {
      onSuccess: () => {
        const state: EmailCodeLocationState = {
          email: payload.email,
          displayName: payload.displayName,
          mobileNumber: payload.mobileNumber,
          resendInSeconds: RESEND_COOLDOWN_SECONDS,
        };
        navigate(AUTH_ROUTES.verifyEmail, { state });
      },
    });
  };

  const goToOtpSignIn = () => {
    const state: LoginLocationState = { signInMethod: 'mobile' };
    navigate(AUTH_ROUTES.login, { state });
  };

  const mobileInUse = signup.error?.code === EMAIL_AUTH_ERRORS.mobileInUse;

  return (
    <AuthLayout
      title="Create your account"
      subtitle="Sign up with your email to start taking jobs on HomeFix."
      below={
        <Typography variant="body2" color="text.secondary" align="center">
          Already have an account?{' '}
          <Link component={RouterLink} to={AUTH_ROUTES.login} underline="hover" fontWeight={700}>
            Sign in
          </Link>
        </Typography>
      }
    >
      <Stack
        component="form"
        spacing={2}
        onSubmit={(event) => void handleSubmit(onSubmit)(event)}
        noValidate
      >
        {signup.error ? (
          mobileInUse ? (
            <Alert
              severity="warning"
              role="alert"
              action={
                <Button color="inherit" size="small" onClick={goToOtpSignIn}>
                  Sign in by OTP
                </Button>
              }
            >
              {signup.error.message}
            </Alert>
          ) : (
            <Alert severity="error" role="alert">
              {signup.error.message}
            </Alert>
          )
        ) : null}

        <Box>
          <FieldLabel>Full name</FieldLabel>
          <TextField
            autoComplete="name"
            fullWidth
            placeholder="Ravi Kumar"
            error={Boolean(errors.displayName)}
            helperText={errors.displayName?.message}
            inputProps={{ 'aria-label': 'Full name' }}
            {...register('displayName')}
          />
        </Box>

        <Box>
          <FieldLabel>Email</FieldLabel>
          <TextField
            type="email"
            autoComplete="email"
            fullWidth
            placeholder="you@example.com"
            error={Boolean(errors.email)}
            helperText={errors.email?.message ?? 'We will email you a 6-digit code to verify it.'}
            inputProps={{ inputMode: 'email', 'aria-label': 'Email' }}
            {...register('email')}
          />
        </Box>

        <Box>
          <FieldLabel>Mobile number</FieldLabel>
          <TextField
            type="tel"
            autoComplete="tel"
            fullWidth
            placeholder="98765 43210"
            error={Boolean(errors.mobileNumber)}
            helperText={errors.mobileNumber?.message ?? 'Customers and HomeFix reach you on it.'}
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
            inputProps={{
              inputMode: 'tel',
              'aria-label': 'Mobile number',
              style: { fontSize: '1.0625rem', letterSpacing: '0.02em' },
            }}
            {...register('mobileNumber')}
          />
        </Box>

        <Box>
          <FieldLabel>Password</FieldLabel>
          <PasswordField
            autoComplete="new-password"
            ariaLabel="Password"
            error={Boolean(errors.password)}
            helperText={errors.password?.message ?? PASSWORD_HINT}
            {...register('password')}
          />
        </Box>

        <Box>
          <FieldLabel>Confirm password</FieldLabel>
          <PasswordField
            autoComplete="new-password"
            ariaLabel="Confirm password"
            error={Boolean(errors.confirmPassword)}
            helperText={errors.confirmPassword?.message}
            {...register('confirmPassword')}
          />
        </Box>

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
    </AuthLayout>
  );
}

/** A field label above its input, as on the sign-in form. */
function FieldLabel({ children }: { children: string }) {
  return (
    <Typography variant="subtitle2" sx={{ mb: 0.75, color: 'text.primary' }}>
      {children}
    </Typography>
  );
}
