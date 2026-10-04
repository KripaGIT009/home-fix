import { useCallback, useEffect, useMemo, useState } from 'react';
import { Link as RouterLink, useLocation, useNavigate } from 'react-router-dom';
import { Alert, Link, Stack, Tab, Tabs, Typography } from '@mui/material';
import PhoneIphoneRoundedIcon from '@mui/icons-material/PhoneIphoneRounded';
import MailOutlineRoundedIcon from '@mui/icons-material/MailOutlineRounded';
import { useAuthStore } from '@stores/authStore';
import { isApiError } from '@api/client';
import { OTP_EXPIRY_SECONDS } from './constants';
import { getLockoutInfo } from './lockout';
import { formatMobileNumber, toE164 } from './phone';
import { GoogleSignInError, requestGoogleIdentityToken } from './googleSignIn';
import { useCountdown } from './useCountdown';
import { useRequestOtp, useSocialLogin, useVerifyOtp } from './hooks';
import { AuthLayout } from './AuthLayout';
import { EmailSignInStep } from './EmailSignInStep';
import { MobileStep } from './MobileStep';
import { OtpStep } from './OtpStep';
import { SocialLoginButtons } from './SocialLoginButtons';
import {
  AUTH_ROUTES,
  DEFAULT_SIGNED_IN_ROUTE,
  readLoginState,
  type SignInMethod,
} from './navigation';
import type { SocialProvider } from './api';
import type { MobileFormValues } from './schemas';

/**
 * Login / OTP screen for Providers (Requirement 1.1–1.5, 28.8).
 *
 * Two steps: (1) enter mobile number and request an OTP, (2) enter the
 * 6-digit code within the expiry window. On successful verification the auth
 * store holds the session and we navigate to the originally requested route
 * (or the Dashboard). Social login (Google, Apple) is offered on the first step.
 *
 * The first step has an Email tab beside the mobile one (email-auth
 * Requirement 2.5): email + password sign-in, with the links to reset a
 * forgotten password and to create an account. It lands in the same place.
 */
export function LoginScreen() {
  const navigate = useNavigate();
  const location = useLocation();
  const isAuthenticated = useAuthStore((state) => state.isAuthenticated);

  const loginState = useMemo(() => readLoginState(location.state), [location.state]);

  const [method, setMethod] = useState<SignInMethod>(loginState.signInMethod ?? 'mobile');
  const [step, setStep] = useState<'mobile' | 'otp'>('mobile');
  // Errors raised before the mutation runs (SDK load, dismissed prompt).
  const [socialError, setSocialError] = useState<string | null>(null);
  // Held in the E.164 form the Auth Service expects, not as typed.
  const [mobileNumber, setMobileNumber] = useState('');

  const requestOtp = useRequestOtp();
  const verifyOtp = useVerifyOtp();
  const socialLogin = useSocialLogin();
  const expiry = useCountdown(0);

  const redirectTo = loginState.from?.pathname ?? DEFAULT_SIGNED_IN_ROUTE;

  // Once a session exists (via OTP verify, email or social login), leave the screen.
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

  const subtitle =
    step === 'otp'
      ? `We sent a 6-digit code to ${formatMobileNumber(mobileNumber)}.`
      : method === 'email'
        ? 'Sign in with your email and password.'
        : 'Sign in with your registered mobile number to start earning.';

  return (
    <AuthLayout
      title={step === 'mobile' ? 'HomeFix for Providers' : 'Verify your number'}
      subtitle={subtitle}
      below={
        step === 'mobile' ? (
          <Typography variant="body2" color="text.secondary" align="center">
            New to HomeFix?{' '}
            <Link component={RouterLink} to={AUTH_ROUTES.signUp} underline="hover" fontWeight={700}>
              Create account
            </Link>
          </Typography>
        ) : null
      }
    >
      {socialErrorMessage ? (
        <Alert severity="error" sx={{ mb: 2 }}>
          {socialErrorMessage}
        </Alert>
      ) : null}

      {step === 'mobile' ? (
        <Stack spacing={3}>
          <Tabs
            value={method}
            onChange={(_event, value: SignInMethod) => setMethod(value)}
            variant="fullWidth"
            aria-label="Sign-in method"
            sx={{ borderBottom: 1, borderColor: 'divider', mt: -1 }}
          >
            <Tab
              value="mobile"
              label="Mobile"
              icon={<PhoneIphoneRoundedIcon fontSize="small" />}
              iconPosition="start"
              sx={{ minHeight: 48 }}
            />
            <Tab
              value="email"
              label="Email"
              icon={<MailOutlineRoundedIcon fontSize="small" />}
              iconPosition="start"
              sx={{ minHeight: 48 }}
            />
          </Tabs>

          {loginState.notice ? (
            <Alert severity="success" role="status">
              {loginState.notice}
            </Alert>
          ) : null}

          {method === 'mobile' ? (
            <MobileStep
              onSubmit={handleRequestOtp}
              isSubmitting={requestOtp.isPending}
              errorMessage={requestErrorMessage}
            />
          ) : (
            <EmailSignInStep
              {...(loginState.email ? { defaultEmail: loginState.email } : {})}
              {...(loginState.from ? { from: loginState.from } : {})}
            />
          )}
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
    </AuthLayout>
  );
}
