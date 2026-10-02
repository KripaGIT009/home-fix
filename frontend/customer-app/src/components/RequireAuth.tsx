import type { ReactNode } from 'react';
import { Navigate, useLocation } from 'react-router-dom';
import { Box, CircularProgress } from '@mui/material';
import { useAuthStore } from '@stores/authStore';

/**
 * Route guard that redirects unauthenticated users to the login screen,
 * preserving the attempted location for post-login redirect.
 */
export function RequireAuth({ children }: { children: ReactNode }) {
  const isAuthenticated = useAuthStore((state) => state.isAuthenticated);
  const isHydrating = useAuthStore((state) => state.isHydrating);
  const location = useLocation();

  // Hold the route until the persisted session has been rehydrated and its
  // silent refresh has settled. Rendering here would mount the screen's queries
  // while the access token is still null: each would 401, then retry behind a
  // second refresh of a single-use token. Redirecting here would be worse --
  // it would bounce a perfectly good session to the login screen on reload.
  if (isHydrating) {
    return (
      <Box sx={{ display: 'grid', placeItems: 'center', minHeight: '100dvh' }}>
        <CircularProgress size={28} aria-label="Restoring your session" />
      </Box>
    );
  }

  if (!isAuthenticated) {
    return <Navigate to="/login" replace state={{ from: location }} />;
  }

  return <>{children}</>;
}
