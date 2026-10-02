import { useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { Box, Stack, Typography } from '@mui/material';
import { keyframes } from '@mui/material/styles';
import { BrandLogo } from '@components/BrandLogo';
import { useAuthStore } from '@stores/authStore';
import { brandGradient } from '@lib/theme';

/** How long the branded splash is shown before navigating away (ms). */
const SPLASH_DURATION_MS = 1500;

const rise = keyframes`
  from { opacity: 0; transform: translateY(8px); }
  to { opacity: 1; transform: none; }
`;

const progress = keyframes`
  from { transform: scaleX(0); }
  to { transform: scaleX(1); }
`;

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
        background: brandGradient,
        position: 'relative',
      }}
    >
      <Stack
        spacing={3}
        alignItems="center"
        textAlign="center"
        sx={{ animation: `${rise} .5s ease-out both` }}
      >
        <BrandLogo size={72} inverted markOnly />
        <Box>
          <Typography variant="h1" component="h1" sx={{ fontSize: { xs: '2.5rem', md: '3rem' } }}>
            HomeFix
          </Typography>
          <Typography variant="h6" component="p" sx={{ opacity: 0.85, mt: 1, fontWeight: 500 }}>
            Verified Help. Anytime. Anywhere.
          </Typography>
        </Box>
        <Box
          role="progressbar"
          aria-label="Loading HomeFix"
          sx={{
            width: 120,
            height: 4,
            borderRadius: 2,
            bgcolor: 'rgba(255,255,255,0.2)',
            overflow: 'hidden',
          }}
        >
          <Box
            sx={{
              height: '100%',
              bgcolor: 'common.white',
              transformOrigin: 'left',
              animation: `${progress} ${SPLASH_DURATION_MS}ms ease-out both`,
            }}
          />
        </Box>
      </Stack>

      <Typography variant="body2" sx={{ position: 'absolute', bottom: 32, opacity: 0.7 }}>
        A safer, happier home is just a tap away.
      </Typography>
    </Box>
  );
}
