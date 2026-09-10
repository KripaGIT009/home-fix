import { useCallback, useEffect, useMemo, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { Alert, Box, Paper, Stack, Typography } from '@mui/material';
import ShieldRoundedIcon from '@mui/icons-material/ShieldRounded';
import { BrandLogo } from '@components/BrandLogo';
import { useAuthStore } from '@stores/authStore';
import { isApiError } from '@api/client';
import { OTP_EXPIRY_SECONDS } from './constants';
import { getLockoutInfo } from './lockout';
import { formatMobileNumber, toE164 } from './phone';
import { useCountdown } from './useCountdown';
import { useRequestOtp, useVerifyOtp } from './hooks';
import { MobileStep } from './MobileStep';
import { OtpStep } from './OtpStep';
import type { MobileFormValues } from './schemas';

interface RedirectState {
  from?: { pathname?: string };
}

/**
 * Admin login / OTP screen (Requirement 1.1–1.4, 28.3).
 *
 * Uses the same OTP flow as the Customer and Provider apps but presents it as a
 * centred desktop card rather than a full-bleed mobile screen. Staff accounts
 * (ADMIN, SUPER_ADMIN, FINANCE_ADMIN, SUPPORT_AGENT) authenticate with their
 * registered mobile number; the Auth Service returns the account's roles which
 * then drive RBAC across the portal. On success we navigate to the originally
 * requested route (or the Dashboard).
 */
export function LoginScreen() {
  const navigate = useNavigate();
  const location = useLocation();
  const isAuthenticated = useAuthStore((state) => state.isAuthenticated);

  const [step, setStep] = useState<'mobile' | 'otp'>('mobile');
  const [mobileNumber, setMobileNumber] = useState('');

  const requestOtp = useRequestOtp();
  const verifyOtp = useVerifyOtp();
  const expiry = useCountdown(0);

  const redirectTo = useMemo(() => {
    const state = location.state as RedirectState | null;
    return state?.from?.pathname ?? '/dashboard';
  }, [location.state]);

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

  const lockout = verifyOtp.isError ? getLockoutInfo(verifyOtp.error) : null;
  const requestErrorMessage =
    requestOtp.isError && isApiError(requestOtp.error) ? requestOtp.error.message : null;

  return (
    <Box
      sx={{
        minHeight: '100vh',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        p: 2,
        background: 'linear-gradient(180deg, #2563EB 0%, #1D4ED8 38%, #F5F7FB 38%)',
      }}
    >
      <Paper sx={{ width: '100%', maxWidth: 440, p: { xs: 3, sm: 4 } }}>
        <Stack spacing={1.5} sx={{ mb: 3 }} alignItems="center" textAlign="center">
          <BrandLogo size={48} />
          <Box>
            <Typography variant="h5" component="h1">
              {step === 'mobile' ? 'Admin console' : 'Verify your number'}
            </Typography>
            <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5 }}>
              {step === 'mobile'
                ? 'Sign in with your registered mobile number.'
                : `We sent a 6-digit code to ${formatMobileNumber(mobileNumber)}.`}
            </Typography>
          </Box>
        </Stack>

        {step === 'mobile' ? (
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

        <Alert
          severity="info"
          variant="outlined"
          sx={{ mt: 3 }}
          icon={<ShieldRoundedIcon fontSize="small" />}
        >
          Staff access only. Unauthorized access is prohibited and logged.
        </Alert>
      </Paper>
    </Box>
  );
}
