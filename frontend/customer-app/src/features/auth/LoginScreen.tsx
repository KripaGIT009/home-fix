import { useCallback, useEffect, useMemo, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { Alert, Box, Card, CardContent, Link, Stack, Typography } from '@mui/material';
import VerifiedRoundedIcon from '@mui/icons-material/VerifiedRounded';
import PaymentsRoundedIcon from '@mui/icons-material/PaymentsRounded';
import BoltRoundedIcon from '@mui/icons-material/BoltRounded';
import { BrandLogo } from '@components/BrandLogo';
import { useAuthStore } from '@stores/authStore';
import { isApiError } from '@api/client';
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

/** Proof points shown beside the form on desktop. */
const VALUE_PROPS = [
  {
    icon: <VerifiedRoundedIcon fontSize="small" />,
    label: 'Verified professionals',
    caption: 'Document and background checks before any job',
  },
  {
    icon: <PaymentsRoundedIcon fontSize="small" />,
    label: 'Upfront pricing',
    caption: 'A full itemised estimate before you confirm',
  },
  {
    icon: <BoltRoundedIcon fontSize="small" />,
    label: 'Emergency service',
    caption: 'A pro dispatched to you, around the clock',
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
 * (or Home). Social login (Google, Apple) is offered on the first step.
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

  const lockout = verifyOtp.isError ? getLockoutInfo(verifyOtp.error) : null;
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
        // Mobile keeps the branded band above the card; from md up it becomes a
        // two-column split so the page fills a desktop viewport instead of
        // stranding a narrow card in the middle of an empty screen.
        gridTemplateColumns: { xs: '1fr', md: '1.05fr 0.95fr' },
      }}
    >
      <Box
        sx={{
          position: 'relative',
          display: 'flex',
          flexDirection: 'column',
          justifyContent: { xs: 'flex-start', md: 'center' },
          px: { xs: 3, md: 8, lg: 12 },
          py: { xs: 4, md: 8 },
          color: 'common.white',
          background: 'linear-gradient(150deg, #2563EB 0%, #1D4ED8 55%, #172554 100%)',
          '&::before': {
            content: '""',
            position: 'absolute',
            inset: 0,
            background:
              'radial-gradient(70% 50% at 20% 20%, rgba(255,255,255,0.16) 0%, rgba(255,255,255,0) 70%)',
            pointerEvents: 'none',
          },
        }}
      >
        <Stack spacing={{ xs: 2, md: 4 }} sx={{ position: 'relative', maxWidth: 520 }}>
          <BrandLogo size={44} inverted />

          <Box>
            <Typography
              component="h1"
              sx={{
                fontSize: { xs: '1.75rem', md: '2.75rem' },
                fontWeight: 800,
                letterSpacing: '-0.03em',
                lineHeight: 1.1,
              }}
            >
              Home services at your doorstep
            </Typography>
            <Typography
              sx={{ opacity: 0.85, mt: 1.5, fontSize: { xs: '0.9375rem', md: '1.0625rem' } }}
            >
              Background-checked professionals, upfront pricing, and live tracking from booking to
              doorstep.
            </Typography>
          </Box>

          {/* The proof points only earn their space once the column is tall. */}
          <Stack spacing={1.5} sx={{ display: { xs: 'none', md: 'flex' }, pt: 1 }}>
            {VALUE_PROPS.map((prop) => (
              <Stack key={prop.label} direction="row" spacing={1.5} alignItems="center">
                <Box
                  sx={{
                    width: 38,
                    height: 38,
                    borderRadius: 2,
                    display: 'grid',
                    placeItems: 'center',
                    bgcolor: 'rgba(255,255,255,0.14)',
                    border: '1px solid rgba(255,255,255,0.18)',
                    flexShrink: 0,
                  }}
                >
                  {prop.icon}
                </Box>
                <Box>
                  <Typography variant="subtitle1" fontWeight={700}>
                    {prop.label}
                  </Typography>
                  <Typography variant="body2" sx={{ opacity: 0.8 }}>
                    {prop.caption}
                  </Typography>
                </Box>
              </Stack>
            ))}
          </Stack>
        </Stack>
      </Box>

      <Box
        sx={{
          display: 'flex',
          flexDirection: 'column',
          justifyContent: 'center',
          alignItems: 'center',
          px: { xs: 2, sm: 3 },
          py: { xs: 0, md: 6 },
          bgcolor: 'background.default',
          // On mobile the card lifts into the band above it.
          mt: { xs: -6, md: 0 },
        }}
      >
        <Box sx={{ width: '100%', maxWidth: 440 }}>
          <Stack spacing={0.5} sx={{ mb: 2, display: { xs: 'none', md: 'block' } }}>
            <Typography variant="h5" component="p">
              {step === 'mobile' ? 'Sign in to continue' : 'Verify your number'}
            </Typography>
            <Typography variant="body2" color="text.secondary">
              {step === 'mobile'
                ? 'We will text you a verification code.'
                : `We sent a 6-digit code to ${formatMobileNumber(mobileNumber)}.`}
            </Typography>
          </Stack>

          <Card>
            <CardContent sx={{ p: { xs: 2.5, sm: 3 } }}>
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
                  />
                  <SocialLoginButtons
                    onSelect={(provider) => void handleSocial(provider)}
                    disabled={socialLogin.isPending || requestOtp.isPending}
                  />
                </Stack>
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
            </CardContent>
          </Card>

          <Typography
            variant="caption"
            color="text.secondary"
            align="center"
            sx={{ mt: 3, display: 'block' }}
          >
            By continuing, you agree to our{' '}
            <Link href="#" underline="hover" fontWeight={600}>
              Terms
            </Link>{' '}
            &amp;{' '}
            <Link href="#" underline="hover" fontWeight={600}>
              Privacy Policy
            </Link>
            .
          </Typography>
        </Box>
      </Box>
    </Box>
  );
}
