import { Box, Button, Stack, Typography } from '@mui/material';
import LockRoundedIcon from '@mui/icons-material/LockRounded';
import { useNavigate } from 'react-router-dom';
import { homePathFor } from '@config/navFilter';
import { useAuthStore } from '@stores/authStore';

/**
 * 403-style screen shown when an authenticated user lacks the role required for
 * a module. Mirrors the backend contract in Requirement 19.7 (ADMIN attempting
 * System Configuration receives a 403 Forbidden) at the UI layer. Every module
 * is role-gated, so this also catches a non-staff session that reached the
 * portal — hence the generic copy.
 *
 * The way out goes to the user's own landing module, not the Dashboard: for
 * staff who cannot see the Dashboard that would only lead back here.
 */
export function ForbiddenScreen() {
  const navigate = useNavigate();
  const roles = useAuthStore((state) => state.user?.roles);
  const home = homePathFor(roles ?? []);
  return (
    <Box role="alert" sx={{ py: 8, display: 'flex', justifyContent: 'center' }}>
      <Stack spacing={2} alignItems="center" textAlign="center" sx={{ maxWidth: 420 }}>
        <LockRoundedIcon color="disabled" sx={{ fontSize: 56 }} aria-hidden />
        <Typography variant="h5" component="h1">
          Access denied
        </Typography>
        <Typography variant="body2" color="text.secondary">
          Your account doesn&apos;t have permission to view this module. Ask a Super Admin if you
          need access.
        </Typography>
        {home ? (
          <Button variant="contained" onClick={() => navigate(home)}>
            {home === '/dashboard' ? 'Back to dashboard' : 'Back to my modules'}
          </Button>
        ) : null}
      </Stack>
    </Box>
  );
}
