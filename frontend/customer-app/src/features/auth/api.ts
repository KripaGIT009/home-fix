import { authClient } from '@api/client';
import type { AuthTokens, UserProfile, UserRole } from '@stores/authStore';

/**
 * Auth Service API bindings (Requirement 1).
 *
 * Endpoints (see design.md - Auth Service):
 * - POST /auth/register/otp     - send OTP to a mobile number
 * - POST /auth/register/verify  - verify OTP, create/return account + tokens
 * - POST /auth/login/social     - social login (Google, Apple)
 *
 * Email sign-up and sign-in (email-auth spec, Requirements 1–3):
 * - POST /auth/register/email          - sign up; emails a 6-digit code
 * - POST /auth/register/email/verify   - enter that code; signs in
 * - POST /auth/register/email/resend   - email a new sign-up code
 * - POST /auth/login/password          - email (or username) + password
 * - POST /auth/password/forgot         - email a reset code
 * - POST /auth/password/reset          - code + new password; ends all sessions
 *
 * Every call goes through `authClient`, which resolves against the Auth Service
 * base URL (VITE_AUTH_BASE_URL, defaulting to the API base path) and carries the
 * X-Correlation-ID interceptor and the normalized ApiError rejection. No
 * Authorization header is sent: these endpoints authenticate by request body.
 * nginx and the Vite dev server both proxy `/api/auth/*` straight to the Auth
 * Service; a native build, having no proxy, points the variable at it directly.
 */

/** Supported social identity providers (Requirement 1.5). */
export type SocialProvider = 'GOOGLE' | 'APPLE';

export interface RequestOtpPayload {
  mobileNumber: string;
}

export interface RequestOtpResponse {
  /** Server-authoritative status, e.g. "OTP_SENT". */
  status: string;
  /** Seconds until the issued OTP expires (server-authoritative, ~300s). */
  expiresInSeconds: number;
}

export interface VerifyOtpPayload {
  mobileNumber: string;
  otp: string;
}

export interface SocialLoginPayload {
  provider: SocialProvider;
  /** The provider's identity token (Google ID token / Apple identity token). */
  identityToken: string;
}

/**
 * The Auth Service's `TokenResponse` — the single body returned by
 * /auth/register/verify, /auth/login/social and /auth/token/refresh alike.
 *
 * There is no nested `user` object: authentication yields the account id and
 * its roles, nothing more. A display name, email or photo exist only once a
 * profile fetch supplies them.
 */
export interface AuthSessionResponse {
  /** Account id; becomes UserProfile.id. */
  userId: string;
  roles: UserRole[];
  accessToken: string;
  refreshToken: string;
  /** Always "Bearer". */
  tokenType: string;
  /** Access token lifetime in seconds. */
  expiresInSeconds: number;
}

/**
 * Normalize a session response into the store's token + profile shapes.
 *
 * The mobile number is threaded in by the caller: the OTP flow already holds it
 * in E.164 form and the response does not carry it. Social login has none to
 * pass, so that profile goes without one rather than inventing a value.
 */
export function toSession(
  response: AuthSessionResponse,
  mobileNumber?: string,
): {
  tokens: AuthTokens;
  user: UserProfile;
} {
  return {
    tokens: { accessToken: response.accessToken, refreshToken: response.refreshToken },
    user: {
      id: response.userId,
      roles: response.roles,
      ...(mobileNumber ? { mobileNumber } : {}),
    },
  };
}

/**
 * Normalize a user-entered mobile number to E.164, which the Auth Service
 * requires (e.g. "+919876543210"). Accepts a bare 10-digit Indian number and
 * prepends the +91 country code; passes through numbers that already start
 * with "+". Non-digits (spaces, dashes) are stripped.
 */
export function toE164(mobileNumber: string): string {
  const trimmed = mobileNumber.trim();
  if (trimmed.startsWith('+')) {
    return '+' + trimmed.slice(1).replace(/\D/g, '');
  }
  const digits = trimmed.replace(/\D/g, '');
  // 10-digit local Indian number -> +91XXXXXXXXXX
  if (digits.length === 10) {
    return `+91${digits}`;
  }
  // 12-digit number already carrying the 91 country code -> +91XXXXXXXXXX
  if (digits.length === 12 && digits.startsWith('91')) {
    return `+${digits}`;
  }
  // Fall back to a best-effort +-prefixed value; the server is authoritative.
  return `+${digits}`;
}

/** POST /auth/register/otp - request an OTP for the given mobile number. */
export async function requestOtp(payload: RequestOtpPayload): Promise<RequestOtpResponse> {
  const { data } = await authClient.post<RequestOtpResponse>('/auth/register/otp', {
    ...payload,
    mobileNumber: toE164(payload.mobileNumber),
  });
  return data;
}

/** POST /auth/register/verify - verify the OTP and create/return a session. */
export async function verifyOtp(payload: VerifyOtpPayload): Promise<AuthSessionResponse> {
  const { data } = await authClient.post<AuthSessionResponse>('/auth/register/verify', {
    ...payload,
    mobileNumber: toE164(payload.mobileNumber),
  });
  return data;
}

/** POST /auth/login/social - authenticate with a social identity token. */
export async function socialLogin(payload: SocialLoginPayload): Promise<AuthSessionResponse> {
  const { data } = await authClient.post<AuthSessionResponse>('/auth/login/social', payload);
  return data;
}

/** Request body for the refresh + logout endpoints. */
export interface RefreshTokenPayload {
  refreshToken: string;
}

/**
 * POST /auth/token/refresh - exchange the persisted refresh token for a fresh
 * access token (Requirement 1.9).
 *
 * Sent on `authClient`: it reaches the Auth Service through the same
 * `/api/auth` route the register calls use, and stays out of the 401 retry
 * interceptor that triggers it. Refresh tokens are single-use and rotate, so
 * the caller must persist `refreshToken` from the response.
 */
export async function refreshSession(payload: RefreshTokenPayload): Promise<AuthSessionResponse> {
  const { data } = await authClient.post<AuthSessionResponse>('/auth/token/refresh', payload);
  return data;
}

/**
 * POST /auth/logout - revoke the refresh token server-side (Requirement 1.12).
 * Idempotent: the service answers 204 even for an already-revoked token.
 */
export async function revokeRefreshToken(payload: RefreshTokenPayload): Promise<void> {
  await authClient.post('/auth/logout', payload);
}

/** The role an email sign-up from this app creates (email-auth Requirement 1.1). */
export const EMAIL_SIGNUP_ROLE = 'CUSTOMER' satisfies UserRole;

export interface EmailSignUpPayload {
  displayName: string;
  email: string;
  /** E.164, e.g. "+919876543210". Not verified at sign-up. */
  mobileNumber: string;
  password: string;
}

/**
 * 202 answer to every request that may email a code. Identical whether or not
 * the address has an account, so it reveals nothing about it.
 */
export interface CodeSentResponse {
  /** Always "CODE_SENT". */
  status: string;
  /** Seconds until the emailed code expires (server-authoritative, ~600s). */
  expiresInSeconds: number;
}

export interface EmailCodePayload {
  email: string;
  code: string;
}

export interface EmailOnlyPayload {
  email: string;
}

export interface PasswordLoginPayload {
  /** An email address (or a console username; customers use their email). */
  identifier: string;
  password: string;
}

export interface PasswordResetPayload {
  email: string;
  code: string;
  newPassword: string;
}

/**
 * POST /auth/register/email - create a pending account and email a code.
 * An address that already has an account is emailed a notice instead, and the
 * answer is the same 202 either way.
 */
export async function signUpWithEmail(payload: EmailSignUpPayload): Promise<CodeSentResponse> {
  const { data } = await authClient.post<CodeSentResponse>('/auth/register/email', {
    ...payload,
    role: EMAIL_SIGNUP_ROLE,
  });
  return data;
}

/** POST /auth/register/email/verify - enter the sign-up code; returns a session. */
export async function verifyEmailSignUp(payload: EmailCodePayload): Promise<AuthSessionResponse> {
  const { data } = await authClient.post<AuthSessionResponse>(
    '/auth/register/email/verify',
    payload,
  );
  return data;
}

/** POST /auth/register/email/resend - email a new sign-up code (once a minute). */
export async function resendSignUpCode(payload: EmailOnlyPayload): Promise<CodeSentResponse> {
  const { data } = await authClient.post<CodeSentResponse>('/auth/register/email/resend', payload);
  return data;
}

/** POST /auth/login/password - sign in with an email and password. */
export async function loginWithPassword(
  payload: PasswordLoginPayload,
): Promise<AuthSessionResponse> {
  const { data } = await authClient.post<AuthSessionResponse>('/auth/login/password', payload);
  return data;
}

/** POST /auth/password/forgot - email a reset code (always 202). */
export async function requestPasswordReset(payload: EmailOnlyPayload): Promise<CodeSentResponse> {
  const { data } = await authClient.post<CodeSentResponse>('/auth/password/forgot', payload);
  return data;
}

/**
 * POST /auth/password/reset - set a new password with the emailed code (204).
 * Every session of the account is ended, so the person signs in afterwards.
 */
export async function resetPassword(payload: PasswordResetPayload): Promise<void> {
  await authClient.post('/auth/password/reset', payload);
}
