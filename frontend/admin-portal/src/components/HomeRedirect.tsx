import { Navigate } from 'react-router-dom';
import { homePathFor } from '@config/navFilter';
import { useAuthStore } from '@stores/authStore';
import { ForbiddenScreen } from './ForbiddenScreen';

/**
 * The `/` route. Sends each user to their landing module rather than always to
 * the Dashboard, which is ADMIN-only: a support agent, dispatcher or finance
 * admin would otherwise sign in straight onto a Forbidden screen. Must sit
 * inside RequireAuth so the session (and its roles) is settled first.
 */
export function HomeRedirect() {
  const roles = useAuthStore((state) => state.user?.roles);
  const home = homePathFor(roles ?? []);
  return home ? <Navigate to={home} replace /> : <ForbiddenScreen />;
}
