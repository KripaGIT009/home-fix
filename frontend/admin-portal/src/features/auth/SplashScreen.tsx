import { useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { Box, Stack, Typography } from '@mui/material';
import HandymanRoundedIcon from '@mui/icons-material/HandymanRounded';
import { useAuthStore } from '@stores/authStore';

/** How long the branded splash is shown before navigating away (ms). */
const SPLASH_DURATION_MS = 1200;

/**
 * Splash screen: shows HomeFix Admin branding briefly, then routes
 * authenticated staff to their landing module ("/" resolves it per role) and
 * everyone else to Login.
 */
export function SplashScreen() {
  const navigate = useNavigate();
  const isAuthenticated = useAuthStore((state) => state.isAuthenticated);

  useEffect(() => {
    const timer = setTimeout(() => {
      navigate(isAuthenticated ? '/' : '/login', { replace: true });
    }, SPLASH_DURATION_MS);
    return () => clearTimeout(timer);
  }, [navigate, isAuthenticated]);

  return (
    <Box
      sx={{
        minHeight: '100vh',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        bgcolor: 'primary.main',
        color: 'primary.contrastText',
        px: 3,
      }}
    >
      <Stack spacing={2} alignItems="center" textAlign="center">
        <HandymanRoundedIcon sx={{ fontSize: 72 }} aria-hidden />
        <Typography variant="h3" component="h1" fontWeight={700}>
          HomeFix Admin
        </Typography>
        <Typography variant="subtitle1" sx={{ opacity: 0.9 }}>
          Platform operations, configuration, and oversight.
        </Typography>
      </Stack>
    </Box>
  );
}
