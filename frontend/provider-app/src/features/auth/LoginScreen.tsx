import { useCallback, useEffect, useMemo, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { Alert, Box, Card, CardContent, Container, Link, Stack, Typography } from '@mui/material';
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

interface RedirectState {
  from?: { pathname?: string };
}

/**
 * Login / OTP screen for Providers (Requirement 1.1–1.5, 28.8).
 *
 * Two steps: (1) enter mobile number and request an OTP, (2) enter the
 * 6-digit code within the expiry window. On successful verification the auth
 * store holds the session and we navigate to the originally requested route
 * (or the Dashboard). Social login (Google, Apple) is offered on the first step.
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
    return state?.from?.pathname ?? '/dashboard';
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
        display: 'flex',
        flexDirection: 'column',
        // Branded band behind the header that the login card overlaps.
        background: 'linear-gradient(180deg, #14B8A6 0%, #0F766E 42%, #F5F7FB 42%)',
      }}
    >
      <Container
        maxWidth="sm"
        sx={{ py: 4, display: 'flex', flexDirection: 'column', flexGrow: 1 }}
      >
        <Stack spacing={1.5} sx={{ color: 'common.white', mb: 3 }}>
          <BrandLogo size={44} inverted showRole />
          <Box>
            <Typography variant="h4" component="h1">
              {step === 'mobile' ? 'HomeFix for Providers' : 'Verify your number'}
            </Typography>
            <Typography variant="body2" sx={{ opacity: 0.85, mt: 0.5 }}>
              {step === 'mobile'
                ? 'Sign in with your registered mobile number to start earning.'
                : `We sent a 6-digit code to ${formatMobileNumber(mobileNumber)}.`}
            </Typography>
          </Box>
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

        <Box sx={{ flexGrow: 1 }} />

        <Typography variant="caption" color="text.secondary" align="center" sx={{ mt: 3 }}>
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
      </Container>
    </Box>
  );
}
