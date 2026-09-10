import { apiClient } from '@api/client';
import type { AuthTokens, UserProfile } from '@stores/authStore';

/**
 * Auth Service API bindings (Requirement 1). Shared across all three portals;
 * the Admin Portal uses the same OTP + social login endpoints, and the Auth
 * Service returns the account's roles (ADMIN / SUPER_ADMIN / FINANCE_ADMIN /
 * SUPPORT_AGENT for staff accounts).
 *
 * Endpoints (see design.md — Auth Service):
 * - POST /auth/login/otp     — send OTP to a mobile number
 * - POST /auth/login/verify  — verify OTP, return account + tokens
 * - POST /auth/login/social  — social login (Google, Apple)
 *
 * Calls are made through the shared Axios client, so they inherit the
 * Authorization + X-Correlation-ID interceptors and the normalized ApiError
 * rejection. The Vite dev server proxies `/api/auth/*` straight to the Auth
 * Service.
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

/** Shared shape returned by the verify + social-login endpoints. */
export interface AuthSessionResponse {
  accessToken: string;
  refreshToken: string;
  user: UserProfile;
}

/** Normalize a session response into the store's token + profile shapes. */
export function toSession(response: AuthSessionResponse): {
  tokens: AuthTokens;
  user: UserProfile;
} {
  return {
    tokens: { accessToken: response.accessToken, refreshToken: response.refreshToken },
    user: response.user,
  };
}

/** POST /auth/login/otp — request an OTP for the given mobile number. */
export async function requestOtp(payload: RequestOtpPayload): Promise<RequestOtpResponse> {
  const { data } = await apiClient.post<RequestOtpResponse>('/auth/login/otp', payload);
  return data;
}

/** POST /auth/login/verify — verify the OTP and return a session. */
export async function verifyOtp(payload: VerifyOtpPayload): Promise<AuthSessionResponse> {
  const { data } = await apiClient.post<AuthSessionResponse>('/auth/login/verify', payload);
  return data;
}

/** POST /auth/login/social — authenticate with a social identity token. */
export async function socialLogin(payload: SocialLoginPayload): Promise<AuthSessionResponse> {
  const { data } = await apiClient.post<AuthSessionResponse>('/auth/login/social', payload);
  return data;
}
