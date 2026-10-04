import { Navigate } from 'react-router-dom';
import { homePathFor } from '@config/navFilter';
import { useAuthStore } from '@stores/authStore';
import { APPLICATION_STATUS_PATH } from './RequireAuth';

/**
 * The `/` route. Sends each user to their landing module rather than always to
 * the Dashboard, which is ADMIN-only: a support agent, dispatcher or finance
 * admin would otherwise sign in straight onto a Forbidden screen. Must sit
 * inside RequireAuth so the session (and its roles) is settled first.
 *
 * Every staff role opens some module, so roles that open none belong to an
 * agency applicant, whose page is its application status (email-auth
 * Requirement 5.5) rather than an access-denied screen.
 */
export function HomeRedirect() {
  const roles = useAuthStore((state) => state.user?.roles);
  const home = homePathFor(roles ?? []);
  return <Navigate to={home ?? APPLICATION_STATUS_PATH} replace />;
}
