import { useCallback, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Alert, Box, Button, Stack, Tab, Tabs, Typography } from '@mui/material';
import ArrowBackRoundedIcon from '@mui/icons-material/ArrowBackRounded';
import { isApiError, isUnreachableError } from '@api/client';
import { brand } from '@lib/theme';
import { OTP_EXPIRY_SECONDS } from './constants';
import { getLockoutInfo } from './lockout';
import { formatMobileNumber, toE164 } from './phone';
import { GoogleSignInError, requestGoogleIdentityToken } from './googleSignIn';
import { useCountdown } from './useCountdown';
import { useRequestOtp, useSocialLogin, useVerifyOtp } from './hooks';
import { useSignedInRedirect, type SignInMethod } from './useSignedInRedirect';
import { AuthLayout } from './AuthLayout';
import { EmailSignInForm, type CodeSentInfo } from './EmailSignInForm';
import { MobileStep } from './MobileStep';
import { OtpStep } from './OtpStep';
import { SocialLoginButtons } from './SocialLoginButtons';
import type { SocialProvider } from './api';
import type { MobileFormValues } from './schemas';

/**
 * Login / OTP screen (Requirement 1.1–1.5, email-auth Requirement 2.5).
 *
 * Two sign-in methods, as tabs. Mobile has two steps: (1) enter mobile number
 * and request an OTP, (2) enter the 6-digit code within the expiry window.
 * Email signs in with email and password, and links to sign-up and password
 * reset. On success the auth store holds the session and we navigate to the
 * originally requested route (or Home). Social login is offered under either
 * method when configured.
 */
export function LoginScreen() {
  const navigate = useNavigate();
  const { state: routeState, forward } = useSignedInRedirect();

  const [method, setMethod] = useState<SignInMethod>(routeState.method ?? 'mobile');
  const [step, setStep] = useState<'mobile' | 'otp'>('mobile');
  // Errors raised before the mutation runs (SDK load, dismissed prompt).
  const [socialError, setSocialError] = useState<string | null>(null);
  // Held in the E.164 form the Auth Service expects, not as typed.
  const [mobileNumber, setMobileNumber] = useState('');

  const requestOtp = useRequestOtp();
  const verifyOtp = useVerifyOtp();
  const socialLogin = useSocialLogin();
  const expiry = useCountdown(0);

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

  const handleEmailCodeSent = useCallback(
    (email: string, info: CodeSentInfo) => {
      navigate('/signup/verify', { state: { ...forward, email, ...info } });
    },
    [navigate, forward],
  );

  const handleForgotPassword = useCallback(
    (email: string) => {
      navigate('/forgot-password', { state: { ...forward, ...(email ? { email } : {}) } });
    },
    [navigate, forward],
  );

  const handleCreateAccount = useCallback(() => {
    navigate('/signup', { state: forward });
  }, [navigate, forward]);

  // Memoised on the error itself: the parent re-renders every second for the
  // countdown, and a fresh object each time would restart the lockout timer.
  const verifyError = verifyOtp.isError ? verifyOtp.error : null;
  const lockout = useMemo(() => (verifyError ? getLockoutInfo(verifyError) : null), [verifyError]);
  const requestErrorMessage =
    requestOtp.isError && isApiError(requestOtp.error) ? requestOtp.error.message : null;
  const socialErrorMessage =
    socialError ??
    (socialLogin.isError && isApiError(socialLogin.error) ? socialLogin.error.message : null);

  const isOtpStep = method === 'mobile' && step === 'otp';

  return (
    <AuthLayout>
      {isOtpStep ? (
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
        {isOtpStep ? 'Enter the code' : 'Sign in to HomeFix'}
      </Typography>
      <Typography variant="body1" color="text.secondary" sx={{ mt: 1, mb: 3 }}>
        {isOtpStep ? (
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
        ) : method === 'mobile' ? (
          'New here? Same steps — we’ll set up your account.'
        ) : (
          'Welcome back. Sign in with your email and password.'
        )}
      </Typography>

      {isOtpStep ? null : (
        <Tabs
          value={method}
          onChange={(_event, next: SignInMethod) => setMethod(next)}
          variant="fullWidth"
          aria-label="Sign-in method"
          sx={{ mb: 3, borderBottom: `1px solid ${brand.line}` }}
        >
          <Tab
            value="mobile"
            label="Mobile number"
            id="signin-tab-mobile"
            aria-controls="signin-panel-mobile"
          />
          <Tab
            value="email"
            label="Email"
            id="signin-tab-email"
            aria-controls="signin-panel-email"
          />
        </Tabs>
      )}

      {socialErrorMessage ? (
        <Alert severity="error" sx={{ mb: 2 }}>
          {socialErrorMessage}
        </Alert>
      ) : null}

      {method === 'email' ? (
        <Stack
          spacing={3}
          role="tabpanel"
          id="signin-panel-email"
          aria-labelledby="signin-tab-email"
        >
          <EmailSignInForm
            {...(routeState.email ? { defaultEmail: routeState.email } : {})}
            onCodeSent={handleEmailCodeSent}
            onForgotPassword={handleForgotPassword}
            onCreateAccount={handleCreateAccount}
            disabled={socialLogin.isPending}
          />
          <SocialLoginButtons
            onSelect={(provider) => void handleSocial(provider)}
            disabled={socialLogin.isPending}
          />
        </Stack>
      ) : step === 'mobile' ? (
        <Stack
          spacing={3}
          role="tabpanel"
          id="signin-panel-mobile"
          aria-labelledby="signin-tab-mobile"
        >
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
    </AuthLayout>
  );
}
