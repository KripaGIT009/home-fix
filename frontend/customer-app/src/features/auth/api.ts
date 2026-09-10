import { apiClient } from '@api/client';
import type { AuthTokens, UserProfile } from '@stores/authStore';

/**
 * Auth Service API bindings (Requirement 1).
 *
 * Endpoints (see design.md - Auth Service):
 * - POST /auth/register/otp     - send OTP to a mobile number
 * - POST /auth/register/verify  - verify OTP, create/return account + tokens
 * - POST /auth/login/social     - social login (Google, Apple)
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
  const { data } = await apiClient.post<RequestOtpResponse>('/auth/register/otp', {
    ...payload,
    mobileNumber: toE164(payload.mobileNumber),
  });
  return data;
}

/** POST /auth/register/verify - verify the OTP and create/return a session. */
export async function verifyOtp(payload: VerifyOtpPayload): Promise<AuthSessionResponse> {
  const { data } = await apiClient.post<AuthSessionResponse>('/auth/register/verify', {
    ...payload,
    mobileNumber: toE164(payload.mobileNumber),
  });
  return data;
}

/** POST /auth/login/social - authenticate with a social identity token. */
export async function socialLogin(payload: SocialLoginPayload): Promise<AuthSessionResponse> {
  const { data } = await apiClient.post<AuthSessionResponse>('/auth/login/social', payload);
  return data;
}
