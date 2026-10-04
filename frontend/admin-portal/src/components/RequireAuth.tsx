import type { ReactNode } from 'react';
import { Navigate, useLocation } from 'react-router-dom';
import { Box, CircularProgress } from '@mui/material';
import { isStaff } from '@config/roles';
import { useAuthStore, type UserRole } from '@stores/authStore';
import { ForbiddenScreen } from './ForbiddenScreen';

/** Where a signed-in account with no staff role belongs (email-auth Requirement 5.5). */
export const APPLICATION_STATUS_PATH = '/agency';

interface RequireAuthProps {
  children: ReactNode;
  /**
   * When provided, the authenticated user must hold at least one of these
   * roles to view the route. Every admin module passes a role set (see
   * @config/roles); System Configuration is SUPER_ADMIN-only (Requirement
   * 19.6/19.7). Unauthorized-but-authenticated users see a 403-style Forbidden
   * screen rather than being bounced to login.
   */
  roles?: readonly UserRole[];
}

/**
 * Route guard. Redirects unauthenticated users to the login screen (preserving
 * the attempted location), and renders a Forbidden screen when the user is
 * authenticated but lacks a required role. An account with no staff role at
 * all is not refused: it is an agency applicant, sent to its application
 * status page (email-auth Requirement 5.5).
 */
export function RequireAuth({ children, roles }: RequireAuthProps) {
  const isAuthenticated = useAuthStore((state) => state.isAuthenticated);
  const isHydrating = useAuthStore((state) => state.isHydrating);
  const user = useAuthStore((state) => state.user);
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

  if (roles && roles.length > 0) {
    const userRoles = user?.roles ?? [];
    const allowed = roles.some((role) => userRoles.includes(role));
    if (!allowed) {
      return isStaff(userRoles) ? (
        <ForbiddenScreen />
      ) : (
        <Navigate to={APPLICATION_STATUS_PATH} replace />
      );
    }
  }

  return <>{children}</>;
}
