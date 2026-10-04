import { accountClient } from '@api/client';
import type { CodeSentResponse } from '@features/auth/api';

/**
 * The signed-in account's own email and password (email-auth Requirement 4).
 *
 * Endpoints (Auth Service, signed in):
 * - GET  /auth/me                - the account's sign-in details
 * - POST /auth/me/email          - email a code to a new address
 * - POST /auth/me/email/verify   - enter that code; the address is saved
 * - PUT  /auth/me/password       - set or change the password
 *
 * They live on the Auth Service like the sign-in calls, but authenticate by
 * the access token, so they go through `accountClient`: the auth base URL with
 * the Authorization header and the 401 refresh-and-replay of `apiClient`. The
 * account is always the token's subject; there is no id in the path.
 */

/** GET /auth/me. */
export interface AccountCredentials {
  userId: string;
  displayName: string | null;
  /** The verified address; null until one has been verified. */
  email: string | null;
  emailVerified: boolean;
  /** E.164. */
  mobileNumber: string | null;
  /** Console username; customers have none. */
  username: string | null;
  /** When true, changing the email or password asks for the current password. */
  hasPassword: boolean;
  roles: string[];
}

export interface EmailChangePayload {
  email: string;
  /** Required when the account has a password. */
  currentPassword?: string;
}

export interface EmailChangeCodePayload {
  code: string;
}

export interface PasswordChangePayload {
  /** Required when the account has a password. */
  currentPassword?: string;
  newPassword: string;
}

/** GET /auth/me - the account's email, mobile and whether it has a password. */
export async function fetchAccount(): Promise<AccountCredentials> {
  const { data } = await accountClient.get<AccountCredentials>('/auth/me');
  return data;
}

/** POST /auth/me/email - email a code to the new address (202). */
export async function requestEmailChange(payload: EmailChangePayload): Promise<CodeSentResponse> {
  const { data } = await accountClient.post<CodeSentResponse>('/auth/me/email', payload);
  return data;
}

/** POST /auth/me/email/verify - save the new address; answers the updated account. */
export async function confirmEmailChange(
  payload: EmailChangeCodePayload,
): Promise<AccountCredentials> {
  const { data } = await accountClient.post<AccountCredentials>('/auth/me/email/verify', payload);
  return data;
}

/** PUT /auth/me/password - set or change the password (204). */
export async function changePassword(payload: PasswordChangePayload): Promise<void> {
  await accountClient.put('/auth/me/password', payload);
}
