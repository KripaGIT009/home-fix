/**
 * Signed-out routes and the router state they hand each other.
 *
 * State (not the query string) carries the email between screens so it never
 * lands in server logs or browser history URLs; the browser keeps it across a
 * reload all the same.
 */

export const AUTH_ROUTES = {
  login: '/login',
  signUp: '/signup',
  /** Enter the emailed sign-up code. */
  verifyEmail: '/signup/verify',
  forgotPassword: '/forgot-password',
} as const;

/** Where OTP and email sign-in land when no protected route was requested. */
export const DEFAULT_SIGNED_IN_ROUTE = '/dashboard';

export type SignInMethod = 'mobile' | 'email';

/** Router state of /login. */
export interface LoginLocationState {
  /** The protected route RequireAuth bounced the user from. */
  from?: { pathname?: string };
  /** Open on this tab, e.g. email after a password reset. */
  signInMethod?: SignInMethod;
  /** Prefills the email field. */
  email?: string;
  /** A success message to show above the form (e.g. "Password changed"). */
  notice?: string;
}

/** Router state of the emailed sign-up code screen. */
export interface EmailCodeLocationState {
  email: string;
  /** Known when arriving from the sign-up form, so the session profile starts with them. */
  displayName?: string;
  mobileNumber?: string;
  /** Seconds before another code may be requested; defaults to the full cooldown. */
  resendInSeconds?: number;
  from?: { pathname?: string };
}

/** Router state of /forgot-password: the email typed on the sign-in form, if any. */
export interface ForgotPasswordLocationState {
  email?: string;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

function optionalString(value: unknown): string | undefined {
  return typeof value === 'string' && value.length > 0 ? value : undefined;
}

/** The /login state, ignoring anything malformed. */
export function readLoginState(state: unknown): LoginLocationState {
  if (!isRecord(state)) return {};
  const fromPath = isRecord(state.from) ? optionalString(state.from.pathname) : undefined;
  const method = state.signInMethod;
  const email = optionalString(state.email);
  const notice = optionalString(state.notice);
  return {
    ...(fromPath ? { from: { pathname: fromPath } } : {}),
    ...(method === 'email' || method === 'mobile' ? { signInMethod: method } : {}),
    ...(email ? { email } : {}),
    ...(notice ? { notice } : {}),
  };
}

/** The code screen's state, or `null` when it was opened without an email (e.g. typed URL). */
export function readEmailCodeState(state: unknown): EmailCodeLocationState | null {
  if (!isRecord(state)) return null;
  const email = optionalString(state.email);
  if (!email) return null;
  const displayName = optionalString(state.displayName);
  const mobileNumber = optionalString(state.mobileNumber);
  const resendInSeconds =
    typeof state.resendInSeconds === 'number' && state.resendInSeconds >= 0
      ? state.resendInSeconds
      : undefined;
  const fromPath = isRecord(state.from) ? optionalString(state.from.pathname) : undefined;
  return {
    email,
    ...(displayName ? { displayName } : {}),
    ...(mobileNumber ? { mobileNumber } : {}),
    ...(resendInSeconds !== undefined ? { resendInSeconds } : {}),
    ...(fromPath ? { from: { pathname: fromPath } } : {}),
  };
}

/** The /forgot-password state, ignoring anything malformed. */
export function readForgotPasswordState(state: unknown): ForgotPasswordLocationState {
  const email = isRecord(state) ? optionalString(state.email) : undefined;
  return email ? { email } : {};
}
