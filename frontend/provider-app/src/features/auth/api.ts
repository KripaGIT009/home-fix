import { authClient, authSessionClient } from '@api/client';
import type { AuthTokens, UserProfile, UserRole } from '@stores/authStore';

/**
 * Auth Service API bindings (Requirement 1). Shared with the Customer App;
 * the Provider App uses the same OTP + social login endpoints and the Auth
 * Service returns the account's roles (SERVICE_PROVIDER for providers).
 *
 * Endpoints (see design.md — Auth Service):
 * - POST /auth/register/otp     — send OTP to a mobile number
 * - POST /auth/register/verify  — verify OTP, create/return account + tokens
 * - POST /auth/login/social     — social login (Google, Apple)
 *
 * and, for email sign-up and sign-in (email-auth spec, Requirements 1–4):
 * - POST /auth/register/email (+ /verify, /resend) — sign up, confirm the emailed code
 * - POST /auth/login/password                      — email + password sign-in
 * - POST /auth/password/forgot, /auth/password/reset — forgotten password
 * - GET /auth/me, POST /auth/me/email (+ /verify), PUT /auth/me/password
 *                                                  — the signed-in account's own credentials
 *
 * Every call goes through `authClient`, which resolves against the Auth Service
 * base URL (VITE_AUTH_BASE_URL, defaulting to the API base path) and carries the
 * X-Correlation-ID interceptor and the normalized ApiError rejection. No
 * Authorization header is sent: these endpoints authenticate by request body.
 * The `/auth/me` calls are the exception: they act on the signed-in account,
 * so they go through `authSessionClient`, which adds the bearer token.
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
 * POST /auth/register/otp — request an OTP for the given mobile number.
 *
 * Always asks for the SERVICE_PROVIDER role: without it the Auth Service
 * registers a CUSTOMER, who could sign in here but would be refused by every
 * provider endpoint. An existing account (say, a customer) gains the role
 * alongside the ones it has (Requirement 1.14).
 */
export async function requestOtp(payload: RequestOtpPayload): Promise<RequestOtpResponse> {
  const { data } = await authClient.post<RequestOtpResponse>('/auth/register/otp', {
    ...payload,
    role: 'SERVICE_PROVIDER',
  });
  return data;
}

/** POST /auth/register/verify — verify the OTP and create/return a session. */
export async function verifyOtp(payload: VerifyOtpPayload): Promise<AuthSessionResponse> {
  const { data } = await authClient.post<AuthSessionResponse>('/auth/register/verify', payload);
  return data;
}

/** POST /auth/login/social — authenticate with a social identity token. */
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

// ---------------------------------------------------------------------------
// Email sign-up, sign-in and password reset (email-auth Requirements 1–3)
// ---------------------------------------------------------------------------

/**
 * The 202 answer to every request that may email a code. It is the same
 * whether or not the address has an account, so it says nothing about it.
 */
export interface CodeSentResponse {
  /** Always "CODE_SENT". */
  status: string;
  /** Seconds until the emailed code expires (10 minutes). */
  expiresInSeconds: number;
}

export interface EmailSignupPayload {
  /** 2–80 characters. */
  displayName: string;
  email: string;
  /** E.164, e.g. +919876543210. */
  mobileNumber: string;
  password: string;
}

export interface EmailCodePayload {
  email: string;
  /** The 6-digit emailed code. */
  code: string;
}

export interface EmailOnlyPayload {
  email: string;
}

export interface PasswordLoginPayload {
  /** An email (or, for staff, a console username). */
  identifier: string;
  password: string;
}

export interface PasswordResetPayload {
  email: string;
  code: string;
  newPassword: string;
}

/**
 * POST /auth/register/email — create an unverified account and email it a code.
 *
 * Asks for the SERVICE_PROVIDER role, for the same reason requestOtp does.
 * Answers 202 even when the email already has an account (that address gets a
 * "you already have an account" email instead); 409 MOBILE_IN_USE when the
 * mobile number belongs to another account.
 */
export async function registerWithEmail(payload: EmailSignupPayload): Promise<CodeSentResponse> {
  const { data } = await authClient.post<CodeSentResponse>('/auth/register/email', {
    ...payload,
    role: 'SERVICE_PROVIDER',
  });
  return data;
}

/**
 * POST /auth/register/email/verify — confirm the sign-up code; activates the
 * account and signs it in. 400 INVALID_CODE (with the attempts left), 410
 * CODE_EXPIRED.
 */
export async function verifyEmailSignup(payload: EmailCodePayload): Promise<AuthSessionResponse> {
  const { data } = await authClient.post<AuthSessionResponse>(
    '/auth/register/email/verify',
    payload,
  );
  return data;
}

/** POST /auth/register/email/resend — a new sign-up code; 429 more than once a minute. */
export async function resendEmailSignupCode(payload: EmailOnlyPayload): Promise<CodeSentResponse> {
  const { data } = await authClient.post<CodeSentResponse>('/auth/register/email/resend', payload);
  return data;
}

/**
 * POST /auth/login/password — email + password sign-in. 401
 * INVALID_CREDENTIALS, 403 EMAIL_NOT_VERIFIED (the password was right but the
 * sign-up code was never entered), 403 ACCOUNT_DISABLED, 429 ACCOUNT_LOCKED
 * with a Retry-After.
 */
export async function passwordLogin(payload: PasswordLoginPayload): Promise<AuthSessionResponse> {
  const { data } = await authClient.post<AuthSessionResponse>('/auth/login/password', payload);
  return data;
}

/** POST /auth/password/forgot — email a reset code; always 202 (no account enumeration). */
export async function requestPasswordReset(payload: EmailOnlyPayload): Promise<CodeSentResponse> {
  const { data } = await authClient.post<CodeSentResponse>('/auth/password/forgot', payload);
  return data;
}

/**
 * POST /auth/password/reset — replace the password (204). Every session of the
 * account is ended, so the user signs in again afterwards.
 */
export async function resetPassword(payload: PasswordResetPayload): Promise<void> {
  await authClient.post('/auth/password/reset', payload);
}

// ---------------------------------------------------------------------------
// The signed-in account's own credentials (email-auth Requirement 4)
// ---------------------------------------------------------------------------

/** GET /auth/me: the account's sign-in details. */
export interface AccountCredentials {
  userId: string;
  displayName: string | null;
  /** Null until an email has been verified. */
  email: string | null;
  emailVerified: boolean;
  mobileNumber: string | null;
  /** Console username; staff only. */
  username: string | null;
  /** Whether a password is set; changing email or password then asks for it. */
  hasPassword: boolean;
  roles: UserRole[];
}

export interface EmailChangePayload {
  email: string;
  /** Required when the account has a password. */
  currentPassword?: string;
}

export interface PasswordChangePayload {
  /** Required when the account has a password. */
  currentPassword?: string;
  newPassword: string;
}

/** GET /auth/me — the signed-in account's email, mobile and whether it has a password. */
export async function fetchAccountCredentials(): Promise<AccountCredentials> {
  const { data } = await authSessionClient.get<AccountCredentials>('/auth/me');
  return data;
}

/**
 * POST /auth/me/email — email a code to the new address (202). 403
 * CURRENT_PASSWORD_INCORRECT, 409 EMAIL_IN_USE, 429.
 */
export async function requestEmailChange(payload: EmailChangePayload): Promise<CodeSentResponse> {
  const { data } = await authSessionClient.post<CodeSentResponse>('/auth/me/email', payload);
  return data;
}

/** POST /auth/me/email/verify — save the new address once its code is entered. */
export async function confirmEmailChange(payload: { code: string }): Promise<AccountCredentials> {
  const { data } = await authSessionClient.post<AccountCredentials>(
    '/auth/me/email/verify',
    payload,
  );
  return data;
}

/**
 * PUT /auth/me/password — set or change the password (204). 400 EMAIL_REQUIRED
 * when the account has no verified email yet, 403 CURRENT_PASSWORD_INCORRECT.
 */
export async function changePassword(payload: PasswordChangePayload): Promise<void> {
  await authSessionClient.put('/auth/me/password', payload);
}
