import { useCallback, useEffect, useMemo, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { Alert, Box, Button, Link, Stack, Typography } from '@mui/material';
import VerifiedUserRoundedIcon from '@mui/icons-material/VerifiedUserRounded';
import ReceiptLongRoundedIcon from '@mui/icons-material/ReceiptLongRounded';
import NearMeRoundedIcon from '@mui/icons-material/NearMeRounded';
import ArrowBackRoundedIcon from '@mui/icons-material/ArrowBackRounded';
import { BrandLogo } from '@components/BrandLogo';
import { useAuthStore } from '@stores/authStore';
import { isApiError, isUnreachableError } from '@api/client';
import { brand, brandGradient, radius, shadows } from '@lib/theme';
import { OTP_EXPIRY_SECONDS } from './constants';
import { getLockoutInfo } from './lockout';
import { formatMobileNumber, toE164 } from './phone';
import { GoogleSignInError, requestGoogleIdentityToken } from './googleSignIn';
import { useCountdown } from './useCountdown';
import { useRequestOtp, useSocialLogin, useVerifyOtp } from './hooks';
import { MobileStep } from './MobileStep';
import { OtpStep } from './OtpStep';
import { SocialLoginButtons } from './SocialLoginButtons';
import type { SocialProvider } from './api';
import type { MobileFormValues } from './schemas';

/** Proof points shown on the brand panel. */
const VALUE_PROPS = [
  {
    icon: <VerifiedUserRoundedIcon />,
    label: 'Verified professionals',
    caption: 'Document and background checks before any job.',
  },
  {
    icon: <ReceiptLongRoundedIcon />,
    label: 'Upfront pricing',
    caption: 'A full itemised estimate before you confirm.',
  },
  {
    icon: <NearMeRoundedIcon />,
    label: 'Live tracking',
    caption: 'Follow your pro to the door, around the clock.',
  },
] as const;

interface RedirectState {
  from?: { pathname?: string };
}

/**
 * Login / OTP screen (Requirement 1.1–1.5).
 *
 * Two steps: (1) enter mobile number and request an OTP, (2) enter the
 * 6-digit code within the expiry window. On successful verification the auth
 * store holds the session and we navigate to the originally requested route
 * (or Home). Social login is offered on the first step when configured.
 *
 * Desktop is a split layout — brand panel and form — and mobile a single
 * column under a compact brand band.
 */
export function LoginScreen() {
  const navigate = useNavigate();
  const location = useLocation();
  const isAuthenticated = useAuthStore((state) => state.isAuthenticated);

  const [step, setStep] = useState<'mobile' | 'otp'>('mobile');
  // Errors raised before the mutation runs (SDK load, dismissed prompt).
  const [socialError, setSocialError] = useState<string | null>(null);
  // Held in the E.164 form the Auth Service expects, not as typed.
  const [mobileNumber, setMobileNumber] = useState('');

  const requestOtp = useRequestOtp();
  const verifyOtp = useVerifyOtp();
  const socialLogin = useSocialLogin();
  const expiry = useCountdown(0);

  const redirectTo = useMemo(() => {
    const state = location.state as RedirectState | null;
    return state?.from?.pathname ?? '/home';
  }, [location.state]);

  // Once a session exists (via OTP verify or social login), leave the screen.
  useEffect(() => {
    if (isAuthenticated) {
      navigate(redirectTo, { replace: true });
    }
  }, [isAuthenticated, navigate, redirectTo]);

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

  const handleSocial = useCallback(
    async (provider: SocialProvider) => {
      // The Auth Service verifies a genuine provider-issued OIDC token, so the
      // browser must obtain one from the provider SDK first.
      if (provider !== 'GOOGLE') {
        setSocialError(`${provider} sign-in is not configured in this build.`);
        return;
      }
      setSocialError(null);
      try {
        const identityToken = await requestGoogleIdentityToken();
        socialLogin.mutate({ provider, identityToken });
      } catch (cause) {
        setSocialError(
          cause instanceof GoogleSignInError ? cause.message : 'Google sign-in failed.',
        );
      }
    },
    [socialLogin],
  );

  // Memoised on the error itself: the parent re-renders every second for the
  // countdown, and a fresh object each time would restart the lockout timer.
  const verifyError = verifyOtp.isError ? verifyOtp.error : null;
  const lockout = useMemo(() => (verifyError ? getLockoutInfo(verifyError) : null), [verifyError]);
  const requestErrorMessage =
    requestOtp.isError && isApiError(requestOtp.error) ? requestOtp.error.message : null;
  const socialErrorMessage =
    socialError ??
    (socialLogin.isError && isApiError(socialLogin.error) ? socialLogin.error.message : null);

  return (
    <Box
      sx={{
        minHeight: '100dvh',
        display: 'grid',
        gridTemplateColumns: { xs: '1fr', md: 'minmax(0, 1fr) minmax(0, 1fr)' },
        bgcolor: 'background.default',
      }}
    >
      <BrandPanel />

      <Box
        component="main"
        sx={{
          display: 'flex',
          flexDirection: 'column',
          alignItems: 'center',
          justifyContent: { xs: 'flex-start', md: 'center' },
          px: { xs: 2, sm: 3 },
          pb: { xs: 4, md: 6 },
          pt: { md: 6 },
          mt: { xs: -5, md: 0 },
          position: 'relative',
        }}
      >
        <Box
          sx={{
            width: '100%',
            maxWidth: 440,
            p: { xs: 3, sm: 4 },
            bgcolor: 'background.paper',
            borderRadius: `${radius.xl}px`,
            border: `1px solid ${brand.line}`,
            boxShadow: { xs: shadows.raised, md: shadows.card },
          }}
        >
          {step === 'otp' ? (
            <Button
              size="small"
              startIcon={<ArrowBackRoundedIcon />}
              onClick={handleChangeNumber}
              disabled={verifyOtp.isPending}
              sx={{ ml: -1, mb: 1.5, color: 'text.secondary' }}
            >
              Back
            </Button>
          ) : null}

          <Typography variant="h3" component="h1">
            {step === 'mobile' ? 'Sign in to HomeFix' : 'Enter the code'}
          </Typography>
          <Typography variant="body1" color="text.secondary" sx={{ mt: 1, mb: 3 }}>
            {step === 'mobile' ? (
              'New here? Same steps — we’ll set up your account.'
            ) : (
              <>
                We sent a 6-digit code to{' '}
                <Box
                  component="span"
                  sx={{ color: 'text.primary', fontWeight: 700, whiteSpace: 'nowrap' }}
                >
                  {formatMobileNumber(mobileNumber)}
                </Box>
                .
              </>
            )}
          </Typography>

          {socialErrorMessage ? (
            <Alert severity="error" sx={{ mb: 2 }}>
              {socialErrorMessage}
            </Alert>
          ) : null}

          {step === 'mobile' ? (
            <Stack spacing={3}>
              <MobileStep
                onSubmit={handleRequestOtp}
                isSubmitting={requestOtp.isPending}
                errorMessage={requestErrorMessage}
                errorSeverity={isUnreachableError(requestOtp.error) ? 'warning' : 'error'}
              />
              <SocialLoginButtons
                onSelect={(provider) => void handleSocial(provider)}
                disabled={socialLogin.isPending || requestOtp.isPending}
              />
            </Stack>
          ) : (
            <Stack spacing={2}>
              {requestErrorMessage ? (
                <Alert severity={isUnreachableError(requestOtp.error) ? 'warning' : 'error'}>
                  {requestErrorMessage}
                </Alert>
              ) : null}
              <OtpStep
                onVerify={handleVerifyOtp}
                onResend={handleResend}
                onChangeNumber={handleChangeNumber}
                isVerifying={verifyOtp.isPending}
                isResending={requestOtp.isPending}
                secondsLeft={expiry.secondsLeft}
                lockout={lockout}
              />
            </Stack>
          )}
        </Box>

        <Typography
          variant="caption"
          color="text.secondary"
          align="center"
          sx={{ mt: 3, display: 'block', maxWidth: 360 }}
        >
          By continuing, you agree to our{' '}
          <Link href="#" underline="hover" fontWeight={600}>
            Terms
          </Link>{' '}
          and{' '}
          <Link href="#" underline="hover" fontWeight={600}>
            Privacy Policy
          </Link>
          .
        </Typography>
      </Box>
    </Box>
  );
}

/** The brand side of the split: full-height panel on desktop, a band on mobile. */
function BrandPanel() {
  return (
    <Box
      component="aside"
      aria-label="About HomeFix"
      sx={{
        position: 'relative',
        overflow: 'hidden',
        color: 'common.white',
        background: brandGradient,
        px: { xs: 3, md: 7, lg: 10 },
        pt: { xs: 3, md: 6 },
        pb: { xs: 9, md: 6 },
        display: 'flex',
        flexDirection: 'column',
      }}
    >
      <PatternDecoration />
      <Box sx={{ position: 'relative' }}>
        <BrandLogo size={40} inverted />
      </Box>

      <Box
        sx={{
          position: 'relative',
          flexGrow: 1,
          display: 'flex',
          flexDirection: 'column',
          justifyContent: 'center',
          maxWidth: 520,
          mt: { xs: 3, md: 0 },
        }}
      >
        <Typography
          component="p"
          sx={{
            fontSize: { xs: '1.625rem', md: '2.75rem' },
            fontWeight: 800,
            letterSpacing: '-0.03em',
            lineHeight: 1.12,
          }}
        >
          Verified help for every home.
        </Typography>
        <Typography
          sx={{
            opacity: 0.85,
            mt: { xs: 1, md: 2 },
            fontSize: { xs: '0.9375rem', md: '1.125rem' },
          }}
        >
          Book trusted pros for repairs, cleaning and more — with the price upfront and live
          tracking to your door.
        </Typography>

        <Stack spacing={2.5} sx={{ display: { xs: 'none', md: 'flex' }, mt: 6 }}>
          {VALUE_PROPS.map((prop) => (
            <Stack key={prop.label} direction="row" spacing={2} alignItems="center">
              <Box
                aria-hidden
                sx={{
                  width: 44,
                  height: 44,
                  borderRadius: `${radius.md}px`,
                  display: 'grid',
                  placeItems: 'center',
                  bgcolor: 'rgba(255,255,255,0.12)',
                  border: '1px solid rgba(255,255,255,0.16)',
                  color: brand.warm,
                  flexShrink: 0,
                }}
              >
                {prop.icon}
              </Box>
              <Box>
                <Typography variant="subtitle1" fontWeight={700}>
                  {prop.label}
                </Typography>
                <Typography variant="body2" sx={{ opacity: 0.78 }}>
                  {prop.caption}
                </Typography>
              </Box>
            </Stack>
          ))}
        </Stack>
      </Box>

      <Typography
        variant="caption"
        sx={{ position: 'relative', opacity: 0.6, display: { xs: 'none', md: 'block' } }}
      >
        © {new Date().getFullYear()} HomeFix · Verified Help. Anytime. Anywhere.
      </Typography>
    </Box>
  );
}

/** Soft concentric rings and a house outline, drawn in SVG — no images. */
function PatternDecoration() {
  return (
    <Box
      component="svg"
      aria-hidden
      viewBox="0 0 600 600"
      sx={{
        position: 'absolute',
        right: { xs: -180, md: -140 },
        bottom: { xs: -260, md: -120 },
        width: { xs: 420, md: 620 },
        height: 'auto',
        opacity: 0.5,
        pointerEvents: 'none',
      }}
    >
      {[120, 190, 260].map((r) => (
        <circle
          key={r}
          cx="300"
          cy="300"
          r={r}
          fill="none"
          stroke="rgba(255,255,255,0.16)"
          strokeWidth="1.5"
        />
      ))}
      <path
        d="M220 320 300 250l80 70M238 306v84h124v-84"
        fill="none"
        stroke="rgba(255,255,255,0.35)"
        strokeWidth="8"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </Box>
  );
}
