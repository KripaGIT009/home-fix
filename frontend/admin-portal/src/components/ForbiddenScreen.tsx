import { Box, Button, Stack, Typography } from '@mui/material';
import LockRoundedIcon from '@mui/icons-material/LockRounded';
import { useNavigate } from 'react-router-dom';

/**
 * 403-style screen shown when an authenticated user lacks the role required for
 * a module. Mirrors the backend contract in Requirement 19.7 (ADMIN attempting
 * System Configuration receives a 403 Forbidden) at the UI layer.
 */
export function ForbiddenScreen() {
  const navigate = useNavigate();
  return (
    <Box role="alert" sx={{ py: 8, display: 'flex', justifyContent: 'center' }}>
      <Stack spacing={2} alignItems="center" textAlign="center" sx={{ maxWidth: 420 }}>
        <LockRoundedIcon color="disabled" sx={{ fontSize: 56 }} aria-hidden />
        <Typography variant="h5" component="h1">
          Access denied
        </Typography>
        <Typography variant="body2" color="text.secondary">
          You don&apos;t have permission to view this module. System Configuration is restricted to
          Super Admins.
        </Typography>
        <Button variant="contained" onClick={() => navigate('/dashboard')}>
          Back to dashboard
        </Button>
      </Stack>
    </Box>
  );
}
