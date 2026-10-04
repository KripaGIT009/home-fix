import { authClient } from '@api/client';
import type { AuthTokens, UserProfile, UserRole } from '@stores/authStore';

/**
 * Auth Service API bindings (Requirement 1). Shared across all three portals;
 * the Admin Portal uses the same OTP + social login endpoints, and the Auth
 * Service returns the account's roles (ADMIN / SUPER_ADMIN / FINANCE_ADMIN /
 * SUPPORT_AGENT for staff accounts).
 *
 * Endpoints (see design.md — Auth Service):
 * - POST /auth/register/otp     — send OTP to a mobile number
 * - POST /auth/register/verify  — verify OTP, return account + tokens
 * - POST /auth/login/password   — email-or-username + password sign-in
 * - POST /auth/login/social     — social login (Google, Apple)
 * - POST /auth/token/refresh    — rotate the refresh token, mint an access token
 * - POST /auth/logout           — revoke the refresh token
 *
 * Email sign-up, password reset and staff invitations (email-auth spec):
 * - POST /auth/register/email                  — create an unverified account, email a code
 * - POST /auth/register/email/verify           — enter the code, return a session
 * - POST /auth/register/email/resend           — email a new code
 * - POST /auth/password/forgot                 — email a reset code
 * - POST /auth/password/reset                  — set a new password with the code
 * - GET  /auth/invitations/{token}             — what a staff invitation offers
 * - POST /auth/invitations/{token}/acceptance  — accept it, return a session
 *
 * The OTP pair lives under `/auth/register/**` for every client: the Auth
 * Service exposes no `/auth/login/otp` or `/auth/login/verify`, and verifying an
 * OTP both creates the account on first use and signs an existing one in.
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

export interface PasswordLoginPayload {
  /** An email address or a console username; the service tells them apart. */
  identifier: string;
  password: string;
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

/** POST /auth/register/otp — request an OTP for the given mobile number. */
export async function requestOtp(payload: RequestOtpPayload): Promise<RequestOtpResponse> {
  const { data } = await authClient.post<RequestOtpResponse>('/auth/register/otp', payload);
  return data;
}

/** POST /auth/register/verify — verify the OTP and return a session. */
export async function verifyOtp(payload: VerifyOtpPayload): Promise<AuthSessionResponse> {
  const { data } = await authClient.post<AuthSessionResponse>('/auth/register/verify', payload);
  return data;
}

/**
 * POST /auth/login/password — authenticate with an email or username and a
 * password.
 *
 * Returns the same session body as the OTP and social paths. Unlike OTP
 * verification this never creates an account. A wrong identifier and a wrong
 * password are answered identically (401 INVALID_CREDENTIALS), so the form must
 * not try to tell the user which it was. A right password on an email sign-up
 * whose code was never entered answers 403 EMAIL_NOT_VERIFIED instead.
 */
export async function passwordLogin(payload: PasswordLoginPayload): Promise<AuthSessionResponse> {
  const { data } = await authClient.post<AuthSessionResponse>('/auth/login/password', payload);
  return data;
}

/** Roles an email sign-up may ask for; the portal's agency applicants use CUSTOMER. */
export type EmailSignupRole = 'CUSTOMER' | 'SERVICE_PROVIDER';

export interface EmailSignupPayload {
  displayName: string;
  email: string;
  /** E.164. */
  mobileNumber: string;
  password: string;
  role: EmailSignupRole;
}

/**
 * The 202 answer to every request that may email a code. It is the same
 * whether or not the address has an account (no enumeration), so the UI must
 * never read anything into it beyond "check your inbox".
 */
export interface CodeSentResponse {
  status: string;
  /** Seconds until the emailed code expires (~600s). */
  expiresInSeconds: number;
}

export interface EmailCodePayload {
  email: string;
  code: string;
}

export interface PasswordResetPayload {
  email: string;
  code: string;
  newPassword: string;
}

/** Staff roles an invitation can carry; SUPER_ADMIN is never one of them. */
export type InvitationRole = 'ADMIN' | 'FINANCE_ADMIN' | 'DISPATCHER' | 'SUPPORT_AGENT';

/** GET /auth/invitations/{token}: what the invitee is about to accept. */
export interface InvitationPreview {
  email: string;
  role: InvitationRole;
  invitedByName?: string | null;
  expiresAt: string;
  /** True when the email already has an account: only its password is asked for. */
  existingAccount: boolean;
}

/**
 * Body of the acceptance call. A new account sends its name, mobile and a new
 * password; an existing account sends only its current password.
 */
export type InvitationAcceptancePayload =
  { displayName: string; mobileNumber: string; password: string } | { password: string };

/** POST /auth/register/email — create an unverified account and email a code. */
export async function registerWithEmail(payload: EmailSignupPayload): Promise<CodeSentResponse> {
  const { data } = await authClient.post<CodeSentResponse>('/auth/register/email', payload);
  return data;
}

/** POST /auth/register/email/verify — enter the emailed code; signs the account in. */
export async function verifyEmailSignup(payload: EmailCodePayload): Promise<AuthSessionResponse> {
  const { data } = await authClient.post<AuthSessionResponse>(
    '/auth/register/email/verify',
    payload,
  );
  return data;
}

/** POST /auth/register/email/resend — email a new sign-up code (once a minute at most). */
export async function resendEmailCode(email: string): Promise<CodeSentResponse> {
  const { data } = await authClient.post<CodeSentResponse>('/auth/register/email/resend', {
    email,
  });
  return data;
}

/** POST /auth/password/forgot — email a reset code if the address has an account. */
export async function forgotPassword(email: string): Promise<CodeSentResponse> {
  const { data } = await authClient.post<CodeSentResponse>('/auth/password/forgot', { email });
  return data;
}

/**
 * POST /auth/password/reset — replace the password. Every session of the
 * account ends, so the person signs in again afterwards.
 */
export async function resetPassword(payload: PasswordResetPayload): Promise<void> {
  await authClient.post('/auth/password/reset', payload);
}

/** GET /auth/invitations/{token} — 410 INVITATION_EXPIRED for a used, revoked or old link. */
export async function fetchInvitation(token: string): Promise<InvitationPreview> {
  const { data } = await authClient.get<InvitationPreview>(
    `/auth/invitations/${encodeURIComponent(token)}`,
  );
  return data;
}

/** POST /auth/invitations/{token}/acceptance — signs the invitee in with the staff role. */
export async function acceptInvitation(
  token: string,
  payload: InvitationAcceptancePayload,
): Promise<AuthSessionResponse> {
  const { data } = await authClient.post<AuthSessionResponse>(
    `/auth/invitations/${encodeURIComponent(token)}/acceptance`,
    payload,
  );
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
