import { useCallback, useEffect, useMemo, useState } from 'react';
import type { SyntheticEvent } from 'react';
import { Link as RouterLink, useLocation, useNavigate } from 'react-router-dom';
import {
  Alert,
  Box,
  Button,
  Divider,
  Link,
  Paper,
  Stack,
  Tab,
  Tabs,
  Typography,
} from '@mui/material';
import ShieldRoundedIcon from '@mui/icons-material/ShieldRounded';
import InsightsRoundedIcon from '@mui/icons-material/InsightsRounded';
import VerifiedUserRoundedIcon from '@mui/icons-material/VerifiedUserRounded';
import BoltRoundedIcon from '@mui/icons-material/BoltRounded';
import { BrandLogo } from '@components/BrandLogo';
import { useAuthStore } from '@stores/authStore';
import { isApiError } from '@api/client';
import { canAccessPath } from '@config/navFilter';
import { brand } from '@lib/theme';
import { EMAIL_NOT_VERIFIED_CODE, OTP_EXPIRY_SECONDS } from './constants';
import { EmailCodeStep } from './EmailCodeStep';
import { ForgotPasswordFlow } from './ForgotPasswordFlow';
import { getLockoutInfo } from './lockout';
import { formatMobileNumber, toE164 } from './phone';
import { useCountdown } from './useCountdown';
import { usePasswordLogin, useRequestOtp, useResendEmailCode, useVerifyOtp } from './hooks';
import { MobileStep } from './MobileStep';
import { OtpStep } from './OtpStep';
import { PasswordStep } from './PasswordStep';
import type { CredentialsFormValues, MobileFormValues } from './schemas';

interface RedirectState {
  from?: { pathname?: string };
}

/** The two ways into the console. Password is first because staff use it daily. */
type Method = 'password' | 'otp';

/**
 * What the card shows besides the sign-in methods: the emailed code of an
 * email sign-up that was never verified (email-auth Requirement 2.3), or the
 * password reset (Requirement 3).
 */
type View =
  | { kind: 'signin' }
  | { kind: 'verify-email'; email: string; expiresInSeconds: number }
  | { kind: 'forgot' };

/** What the brand panel promises. Kept short: it is scenery, not documentation. */
const HIGHLIGHTS = [
  {
    icon: InsightsRoundedIcon,
    title: 'Operations at a glance',
    body: 'Live bookings, dispatch health and settlement in one console.',
  },
  {
    icon: VerifiedUserRoundedIcon,
    title: 'Verified supply',
    body: 'Review provider documents and background checks before they go live.',
  },
  {
    icon: BoltRoundedIcon,
    title: 'Act in the moment',
    body: 'Reassign jobs, resolve complaints and issue refunds without leaving the queue.',
  },
] as const;

/**
 * Admin sign-in (Requirement 1.1-1.4, 28.3; email-auth Requirements 2.5, 3, 5.1).
 *
 * Two panels on desktop: a brand panel that says what the console is for, and
 * the form. Below the `md` breakpoint the brand panel is dropped rather than
 * stacked - on a phone it would push the form below the fold, and the point of
 * a sign-in screen is the form.
 *
 * Two methods share the screen. Password is the default because it is the one
 * staff use every day; OTP stays available because it is the only way into an
 * account that has no credentials provisioned yet. Both end in the same place:
 * a session whose roles drive RBAC across the portal. A session without a staff
 * role is an agency applicant's: the route guards take it to its application
 * status page.
 *
 * The password method also carries "Forgot password?", and an email sign-up
 * whose code was never entered is offered a new code here rather than a dead
 * end. Agencies that have no account yet start from "Register your agency".
 */
export function LoginScreen() {
  const navigate = useNavigate();
  const location = useLocation();
  const isAuthenticated = useAuthStore((state) => state.isAuthenticated);
  const roles = useAuthStore((state) => state.user?.roles);
  const signOutNotice = useAuthStore((state) => state.signOutNotice);

  const [method, setMethod] = useState<Method>('password');
  const [step, setStep] = useState<'mobile' | 'otp'>('mobile');
  const [mobileNumber, setMobileNumber] = useState('');
  const [view, setView] = useState<View>({ kind: 'signin' });
  const [passwordChanged, setPasswordChanged] = useState(false);

  const passwordLogin = usePasswordLogin();
  const resendEmailCode = useResendEmailCode();
  const requestOtp = useRequestOtp();
  const verifyOtp = useVerifyOtp();
  const expiry = useCountdown(0);

  // Back to where the user was headed, if their roles open it; otherwise "/",
  // which resolves to their own landing module. Defaulting to the Dashboard
  // would strand support agents, dispatchers and finance on a Forbidden screen.
  const redirectTo = useMemo(() => {
    const state = location.state as RedirectState | null;
    const from = state?.from?.pathname;
    return from && canAccessPath(roles ?? [], from) ? from : '/';
  }, [location.state, roles]);

  useEffect(() => {
    if (isAuthenticated) {
      navigate(redirectTo, { replace: true });
    }
  }, [isAuthenticated, navigate, redirectTo]);

  const handlePasswordLogin = useCallback(
    (values: CredentialsFormValues) => {
      setPasswordChanged(false);
      resendEmailCode.reset();
      passwordLogin.mutate(values);
    },
    [passwordLogin, resendEmailCode],
  );

  /**
   * The password was right but the sign-up code was never entered: send a new
   * code to the identifier (an email, since only email sign-ups are unverified)
   * and open the code screen. Entering it signs the account in.
   */
  const handleResendSignupCode = useCallback(() => {
    const email = passwordLogin.variables?.identifier.trim();
    if (!email) return;
    resendEmailCode.mutate(email, {
      onSuccess: (data) => {
        passwordLogin.reset();
        setView({ kind: 'verify-email', email, expiresInSeconds: data.expiresInSeconds });
      },
    });
  }, [passwordLogin, resendEmailCode]);

  const handleBackToSignIn = useCallback(() => {
    passwordLogin.reset();
    resendEmailCode.reset();
    setView({ kind: 'signin' });
  }, [passwordLogin, resendEmailCode]);

  const handleRequestOtp = useCallback(
    ({ mobileNumber: submitted }: MobileFormValues) => {
      const e164 = toE164(submitted);
      requestOtp.mutate(
        { mobileNumber: e164 },
        {
          onSuccess: (data) => {
            setMobileNumber(e164);
            setStep('otp');
            expiry.reset(data.expiresInSeconds || OTP_EXPIRY_SECONDS);
          },
        },
      );
    },
    [requestOtp, expiry],
  );

  const handleVerifyOtp = useCallback(
    (otp: string) => {
      verifyOtp.mutate({ mobileNumber, otp });
    },
    [verifyOtp, mobileNumber],
  );

  const handleResend = useCallback(() => {
    verifyOtp.reset();
    handleRequestOtp({ mobileNumber });
  }, [verifyOtp, handleRequestOtp, mobileNumber]);

  const handleChangeNumber = useCallback(() => {
    verifyOtp.reset();
    requestOtp.reset();
    expiry.reset(0);
    setStep('mobile');
  }, [verifyOtp, requestOtp, expiry]);

  /**
   * Switching method clears the other one's error. Leaving a stale "Incorrect
   * username or password" above an OTP field would read as a failure of the
   * code that was never submitted.
   */
  const handleMethodChange = useCallback(
    (_event: SyntheticEvent, next: Method) => {
      passwordLogin.reset();
      resendEmailCode.reset();
      requestOtp.reset();
      verifyOtp.reset();
      expiry.reset(0);
      setStep('mobile');
      setPasswordChanged(false);
      setMethod(next);
    },
    [passwordLogin, resendEmailCode, requestOtp, verifyOtp, expiry],
  );

  const lockout = verifyOtp.isError ? getLockoutInfo(verifyOtp.error) : null;
  const requestErrorMessage =
    requestOtp.isError && isApiError(requestOtp.error) ? requestOtp.error.message : null;
  const emailNotVerified =
    passwordLogin.isError && passwordLogin.error.code === EMAIL_NOT_VERIFIED_CODE;
  // A refused resend (e.g. 429, once a minute) replaces the not-verified notice.
  const passwordErrorMessage =
    resendEmailCode.isError && isApiError(resendEmailCode.error)
      ? resendEmailCode.error.message
      : emailNotVerified
        ? 'Your email address is not verified yet. We can email you a new code to finish signing up.'
        : passwordLogin.isError && isApiError(passwordLogin.error)
          ? passwordLogin.error.message
          : null;

  const otpCodeStep = method === 'otp' && step === 'otp';
  const heading =
    view.kind === 'verify-email'
      ? 'Verify your email'
      : view.kind === 'forgot'
        ? 'Reset your password'
        : otpCodeStep
          ? 'Verify your number'
          : 'Sign in to the console';
  const subheading =
    view.kind === 'verify-email'
      ? `We sent a 6-digit code to ${view.email}.`
      : view.kind === 'forgot'
        ? 'Enter the email on your account and we will send you a code.'
        : otpCodeStep
          ? `We sent a 6-digit code to ${formatMobileNumber(mobileNumber)}.`
          : 'Use your staff or agency account to continue.';

  return (
    <Box sx={{ minHeight: '100dvh', display: 'flex', bgcolor: 'background.paper' }}>
      {/* Brand panel - desktop only. */}
      <Box
        sx={{
          display: { xs: 'none', md: 'flex' },
          flexDirection: 'column',
          justifyContent: 'space-between',
          width: '44%',
          maxWidth: 620,
          p: 6,
          color: '#FFFFFF',
          position: 'relative',
          overflow: 'hidden',
          background: `linear-gradient(155deg, ${brand.accent} 0%, ${brand.accentDark} 52%, #172554 100%)`,
        }}
      >
        {/* Two soft blooms give the flat gradient depth without an image asset. */}
        <Box
          aria-hidden
          sx={{
            position: 'absolute',
            inset: 0,
            background:
              'radial-gradient(760px circle at 12% 8%, rgba(255,255,255,0.16), transparent 45%),' +
              'radial-gradient(560px circle at 88% 92%, rgba(56,189,248,0.22), transparent 50%)',
          }}
        />

        <Box sx={{ position: 'relative' }}>
          <BrandLogo size={44} inverted />
        </Box>

        <Stack spacing={4.5} sx={{ position: 'relative', py: 4 }}>
          <Box>
            <Typography
              variant="h2"
              component="p"
              sx={{ fontWeight: 800, letterSpacing: '-0.03em', lineHeight: 1.15 }}
            >
              Run the marketplace
              <Box component="span" sx={{ display: 'block', color: 'rgba(255,255,255,0.66)' }}>
                from one console.
              </Box>
            </Typography>
            <Typography
              variant="body1"
              sx={{ mt: 2, maxWidth: 420, color: 'rgba(255,255,255,0.76)' }}
            >
              HomeFix Operations brings bookings, providers, payments and support into a single
              workspace.
            </Typography>
          </Box>

          <Stack spacing={2.75}>
            {HIGHLIGHTS.map(({ icon: Icon, title, body }) => (
              <Stack key={title} direction="row" spacing={2} alignItems="flex-start">
                <Box
                  aria-hidden
                  sx={{
                    width: 40,
                    height: 40,
                    borderRadius: 2.5,
                    flexShrink: 0,
                    display: 'grid',
                    placeItems: 'center',
                    bgcolor: 'rgba(255,255,255,0.14)',
                    border: '1px solid rgba(255,255,255,0.18)',
                  }}
                >
                  <Icon fontSize="small" />
                </Box>
                <Box>
                  <Typography variant="subtitle1" sx={{ fontWeight: 700 }}>
                    {title}
                  </Typography>
                  <Typography variant="body2" sx={{ color: 'rgba(255,255,255,0.68)' }}>
                    {body}
                  </Typography>
                </Box>
              </Stack>
            ))}
          </Stack>
        </Stack>

        <Typography
          variant="caption"
          sx={{ position: 'relative', color: 'rgba(255,255,255,0.55)' }}
        >
          © {new Date().getFullYear()} HomeFix. Internal staff console.
        </Typography>
      </Box>

      {/* Form panel. */}
      <Box
        sx={{
          flex: 1,
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          px: { xs: 2.5, sm: 4 },
          py: { xs: 5, md: 4 },
          bgcolor: { xs: 'background.default', md: 'background.paper' },
        }}
      >
        <Paper
          elevation={0}
          sx={{
            width: '100%',
            maxWidth: 420,
            p: { xs: 3, sm: 4 },
            // On desktop the panel itself is the surface; the card only needs a
            // border on small screens where it sits on the tinted canvas.
            border: { xs: `1px solid ${brand.line}`, md: 'none' },
            bgcolor: 'background.paper',
          }}
        >
          <Stack spacing={1.25} sx={{ mb: 3 }}>
            <Box sx={{ display: { md: 'none' }, mb: 1 }}>
              <BrandLogo size={40} />
            </Box>
            <Typography variant="h4" component="h1">
              {heading}
            </Typography>
            <Typography variant="body2" color="text.secondary">
              {subheading}
            </Typography>
          </Stack>

          {/* A session ended by the server (e.g. the account was suspended) says
              why, instead of silently dropping the user back here. */}
          {signOutNotice ? (
            <Alert severity="warning" sx={{ mb: 2 }}>
              {signOutNotice}
            </Alert>
          ) : null}

          {passwordChanged && view.kind === 'signin' ? (
            <Alert severity="success" sx={{ mb: 2 }}>
              Your password was changed. Sign in with the new one.
            </Alert>
          ) : null}

          {/* Hidden once a code is in flight: switching method there would throw
              away the code the user is part-way through typing. */}
          {otpCodeStep || view.kind !== 'signin' ? null : (
            <Tabs
              value={method}
              onChange={handleMethodChange}
              sx={{
                mb: 3,
                minHeight: 40,
                borderBottom: `1px solid ${brand.line}`,
                '& .MuiTab-root': { minHeight: 40, px: 0, mr: 3, fontSize: '0.875rem' },
              }}
            >
              <Tab value="password" label="Password" />
              <Tab value="otp" label="Mobile OTP" />
            </Tabs>
          )}

          {view.kind === 'verify-email' ? (
            <EmailCodeStep
              email={view.email}
              expiresInSeconds={view.expiresInSeconds}
              onBack={handleBackToSignIn}
              backLabel="Back to sign in"
            />
          ) : view.kind === 'forgot' ? (
            <ForgotPasswordFlow
              initialEmail={passwordLogin.variables?.identifier ?? ''}
              onCancel={handleBackToSignIn}
              onDone={() => {
                setPasswordChanged(true);
                handleBackToSignIn();
              }}
            />
          ) : method === 'password' ? (
            <PasswordStep
              onSubmit={handlePasswordLogin}
              isSubmitting={passwordLogin.isPending || resendEmailCode.isPending}
              errorMessage={passwordErrorMessage}
              errorAction={
                emailNotVerified && !resendEmailCode.isError ? (
                  <Button
                    color="inherit"
                    size="small"
                    onClick={handleResendSignupCode}
                    disabled={resendEmailCode.isPending}
                  >
                    Resend code
                  </Button>
                ) : undefined
              }
              onForgotPassword={() => {
                setPasswordChanged(false);
                setView({ kind: 'forgot' });
              }}
            />
          ) : step === 'mobile' ? (
            <MobileStep
              onSubmit={handleRequestOtp}
              isSubmitting={requestOtp.isPending}
              errorMessage={requestErrorMessage}
            />
          ) : (
            <OtpStep
              onVerify={handleVerifyOtp}
              onResend={handleResend}
              onChangeNumber={handleChangeNumber}
              isVerifying={verifyOtp.isPending}
              secondsLeft={expiry.secondsLeft}
              lockout={lockout}
            />
          )}

          <Divider sx={{ mt: 3.5, mb: 2.5 }} />

          {/* Agency sign-up (email-auth Requirement 5.1): a service company with
              no account yet starts here rather than at the staff form. */}
          <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
            Running a service agency?{' '}
            <Link component={RouterLink} to="/agency/register" fontWeight={600}>
              Register your agency
            </Link>
          </Typography>

          <Alert
            severity="info"
            variant="outlined"
            icon={<ShieldRoundedIcon fontSize="small" />}
            sx={{ border: 'none', bgcolor: brand.accentSoft, px: 1.5, py: 0.75 }}
          >
            <Typography variant="caption" color="text.secondary">
              For HomeFix staff and partner agencies. Sign-in attempts are recorded, and five
              consecutive failures lock the account for 30 minutes.
            </Typography>
          </Alert>
        </Paper>
      </Box>
    </Box>
  );
}
