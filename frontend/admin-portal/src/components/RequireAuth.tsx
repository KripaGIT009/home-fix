import type { ReactNode } from 'react';
import { Navigate, useLocation } from 'react-router-dom';
import { useAuthStore, type UserRole } from '@stores/authStore';
import { ForbiddenScreen } from './ForbiddenScreen';

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
 * authenticated but lacks a required role.
 */
export function RequireAuth({ children, roles }: RequireAuthProps) {
  const isAuthenticated = useAuthStore((state) => state.isAuthenticated);
  const user = useAuthStore((state) => state.user);
  const location = useLocation();

  if (!isAuthenticated) {
    return <Navigate to="/login" replace state={{ from: location }} />;
  }

  if (roles && roles.length > 0) {
    const userRoles = user?.roles ?? [];
    const allowed = roles.some((role) => userRoles.includes(role));
    if (!allowed) {
      return <ForbiddenScreen />;
    }
  }

  return <>{children}</>;
}
