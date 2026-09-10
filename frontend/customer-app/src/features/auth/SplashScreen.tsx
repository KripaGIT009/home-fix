import { useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { Box, Stack, Typography } from '@mui/material';
import VerifiedRoundedIcon from '@mui/icons-material/VerifiedRounded';
import ScheduleRoundedIcon from '@mui/icons-material/ScheduleRounded';
import PlaceRoundedIcon from '@mui/icons-material/PlaceRounded';
import { BrandLogo } from '@components/BrandLogo';
import { useAuthStore } from '@stores/authStore';

/** How long the branded splash is shown before navigating away (ms). */
const SPLASH_DURATION_MS = 1500;

/** The three promises in the brand line, shown as a row of proof points. */
const PROMISES = [
  { icon: <VerifiedRoundedIcon fontSize="small" />, label: 'Verified' },
  { icon: <ScheduleRoundedIcon fontSize="small" />, label: 'Anytime' },
  { icon: <PlaceRoundedIcon fontSize="small" />, label: 'Anywhere' },
] as const;

/**
 * Splash screen (Requirement 28.7): shows HomeFix branding briefly, then
 * routes authenticated users to Home and everyone else to Login.
 */
export function SplashScreen() {
  const navigate = useNavigate();
  const isAuthenticated = useAuthStore((state) => state.isAuthenticated);

  useEffect(() => {
    const timer = setTimeout(() => {
      navigate(isAuthenticated ? '/home' : '/login', { replace: true });
    }, SPLASH_DURATION_MS);
    return () => clearTimeout(timer);
  }, [navigate, isAuthenticated]);

  return (
    <Box
      sx={{
        minHeight: '100dvh',
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        justifyContent: 'center',
        px: 4,
        color: 'common.white',
        background: 'linear-gradient(160deg, #2563EB 0%, #1D4ED8 55%, #172554 100%)',
        // Soft radial bloom behind the mark so the flat gradient gains depth.
        '&::before': {
          content: '""',
          position: 'absolute',
          inset: 0,
          background:
            'radial-gradient(60% 40% at 50% 30%, rgba(255,255,255,0.18) 0%, rgba(255,255,255,0) 70%)',
          pointerEvents: 'none',
        },
        position: 'relative',
      }}
    >
      <Stack spacing={2.5} alignItems="center" textAlign="center" sx={{ zIndex: 1 }}>
        <BrandLogo size={76} inverted />
        <Box>
          <Typography variant="h3" component="h1" fontWeight={800}>
            HomeFix
          </Typography>
          <Typography variant="subtitle1" sx={{ opacity: 0.85, mt: 0.5 }}>
            Verified Help. Anytime. Anywhere.
          </Typography>
        </Box>

        <Stack direction="row" spacing={1} sx={{ pt: 1 }}>
          {PROMISES.map((promise) => (
            <Stack
              key={promise.label}
              direction="row"
              spacing={0.75}
              alignItems="center"
              sx={{
                px: 1.5,
                py: 0.75,
                borderRadius: 999,
                bgcolor: 'rgba(255, 255, 255, 0.14)',
                border: '1px solid rgba(255, 255, 255, 0.18)',
              }}
            >
              {promise.icon}
              <Typography variant="caption" fontWeight={700}>
                {promise.label}
              </Typography>
            </Stack>
          ))}
        </Stack>
      </Stack>

      <Typography
        variant="caption"
        sx={{ position: 'absolute', bottom: 32, opacity: 0.7, zIndex: 1 }}
      >
        A safer, happier home is just a tap away.
      </Typography>
    </Box>
  );
}
