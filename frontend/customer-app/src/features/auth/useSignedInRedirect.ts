import { useEffect, useMemo } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { useAuthStore } from '@stores/authStore';

/** How a public auth screen may be opened. */
export type SignInMethod = 'mobile' | 'email';

/**
 * Router state shared by the public auth screens (login, sign-up, email code,
 * password reset). `from` is set by RequireAuth and carried from screen to
 * screen, so a sign-in finished on any of them returns to the page that sent
 * the customer to sign in.
 */
export interface AuthRouteState {
  from?: { pathname?: string };
  /** Which tab the login screen opens on. */
  method?: SignInMethod;
  /** The address a code was sent to, or to prefill the email sign-in with. */
  email?: string;
  /** Seconds the code is valid for, from the 202 that sent it. */
  expiresInSeconds?: number;
  /** Seconds before another code may be requested (defaults to the 60 s limit). */
  resendAfterSeconds?: number;
}

/**
 * Leaves a public auth screen once a session exists (OTP, social, email
 * sign-in or a verified sign-up), for the originally requested route or Home.
 * `enabled: false` keeps a screen open to signed-in visitors — password reset,
 * which someone signed in on this device may still need.
 *
 * Returns the router state, plus `forward` — the part to hand on when moving
 * between auth screens, so the original destination is not lost on the way.
 */
export function useSignedInRedirect(enabled = true): {
  state: AuthRouteState;
  forward: Pick<AuthRouteState, 'from'>;
} {
  const navigate = useNavigate();
  const location = useLocation();
  const isAuthenticated = useAuthStore((store) => store.isAuthenticated);

  const state = useMemo(() => (location.state as AuthRouteState | null) ?? {}, [location.state]);
  const redirectTo = state.from?.pathname ?? '/home';

  useEffect(() => {
    if (enabled && isAuthenticated) {
      navigate(redirectTo, { replace: true });
    }
  }, [enabled, isAuthenticated, navigate, redirectTo]);

  const forward = useMemo(() => (state.from ? { from: state.from } : {}), [state.from]);
  return { state, forward };
}
