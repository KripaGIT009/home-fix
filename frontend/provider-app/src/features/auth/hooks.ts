import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { UseMutationResult, UseQueryResult } from '@tanstack/react-query';
import { useAuthStore } from '@stores/authStore';
import type { ApiError } from '@api/client';
import {
  changePassword,
  confirmEmailChange,
  fetchAccountCredentials,
  passwordLogin,
  registerWithEmail,
  requestEmailChange,
  requestOtp,
  requestPasswordReset,
  resendEmailSignupCode,
  resetPassword,
  socialLogin,
  toSession,
  verifyEmailSignup,
  verifyOtp,
  type AccountCredentials,
  type AuthSessionResponse,
  type CodeSentResponse,
  type EmailChangePayload,
  type EmailCodePayload,
  type EmailOnlyPayload,
  type EmailSignupPayload,
  type PasswordChangePayload,
  type PasswordLoginPayload,
  type PasswordResetPayload,
  type RequestOtpPayload,
  type RequestOtpResponse,
  type SocialLoginPayload,
  type VerifyOtpPayload,
} from './api';

/**
 * TanStack Query mutations for the OTP + social login flows (Requirement 1).
 * Mutations never retry (client-error semantics live in queryClient), and the
 * verify/social hooks persist the returned session into the auth store on
 * success.
 */

/** Request an OTP for a mobile number (Requirement 1.1). */
export function useRequestOtp(): UseMutationResult<
  RequestOtpResponse,
  ApiError,
  RequestOtpPayload
> {
  return useMutation<RequestOtpResponse, ApiError, RequestOtpPayload>({
    mutationFn: requestOtp,
  });
}

/** Verify an OTP and establish a session (Requirement 1.2). */
export function useVerifyOtp(): UseMutationResult<AuthSessionResponse, ApiError, VerifyOtpPayload> {
  const setSession = useAuthStore((state) => state.setSession);

  return useMutation<AuthSessionResponse, ApiError, VerifyOtpPayload>({
    mutationFn: verifyOtp,
    // The response carries no mobile number, so the one just verified (already
    // E.164 from the login screen) is threaded through into the profile.
    onSuccess: (response, variables) => {
      const { tokens, user } = toSession(response, variables.mobileNumber);
      setSession(tokens, user);
    },
  });
}

/** Authenticate via a social identity token (Requirement 1.5). */
export function useSocialLogin(): UseMutationResult<
  AuthSessionResponse,
  ApiError,
  SocialLoginPayload
> {
  const setSession = useAuthStore((state) => state.setSession);

  return useMutation<AuthSessionResponse, ApiError, SocialLoginPayload>({
    mutationFn: socialLogin,
    // No mobile number is involved in a social login; the profile goes without
    // one until a profile fetch supplies it.
    onSuccess: (response) => {
      const { tokens, user } = toSession(response);
      setSession(tokens, user);
    },
  });
}

// ---------------------------------------------------------------------------
// Email sign-up, sign-in and password reset (email-auth Requirements 1–3)
// ---------------------------------------------------------------------------

/** Create an account with email + password; the code is emailed (Requirement 1.3). */
export function useEmailSignup(): UseMutationResult<
  CodeSentResponse,
  ApiError,
  EmailSignupPayload
> {
  return useMutation<CodeSentResponse, ApiError, EmailSignupPayload>({
    mutationFn: registerWithEmail,
  });
}

/**
 * The sign-up code plus what the person typed at sign-up, when this device
 * still knows it, so the new session's profile starts with it.
 */
export interface VerifyEmailSignupVariables extends EmailCodePayload {
  mobileNumber?: string;
  displayName?: string;
}

/** Confirm the sign-up code and establish a session (Requirement 1.6). */
export function useVerifyEmailSignup(): UseMutationResult<
  AuthSessionResponse,
  ApiError,
  VerifyEmailSignupVariables
> {
  const setSession = useAuthStore((state) => state.setSession);

  return useMutation<AuthSessionResponse, ApiError, VerifyEmailSignupVariables>({
    mutationFn: ({ email, code }) => verifyEmailSignup({ email, code }),
    // Same session path as OTP verification. The name and mobile were just
    // stored by the Auth Service, so they are facts, not placeholders.
    onSuccess: (response, { email, mobileNumber, displayName }) => {
      const { tokens, user } = toSession(response, mobileNumber);
      setSession(tokens, { ...user, email, ...(displayName ? { displayName } : {}) });
    },
  });
}

/** Email a new sign-up code (at most once a minute — Requirement 1.8). */
export function useResendEmailSignupCode(): UseMutationResult<
  CodeSentResponse,
  ApiError,
  EmailOnlyPayload
> {
  return useMutation<CodeSentResponse, ApiError, EmailOnlyPayload>({
    mutationFn: resendEmailSignupCode,
  });
}

/** Sign in with email + password (Requirement 2). */
export function usePasswordLogin(): UseMutationResult<
  AuthSessionResponse,
  ApiError,
  PasswordLoginPayload
> {
  const setSession = useAuthStore((state) => state.setSession);

  return useMutation<AuthSessionResponse, ApiError, PasswordLoginPayload>({
    mutationFn: passwordLogin,
    onSuccess: (response, variables) => {
      const { tokens, user } = toSession(response);
      setSession(tokens, { ...user, email: variables.identifier });
    },
  });
}

/** Email a password-reset code (Requirement 3.1). */
export function useRequestPasswordReset(): UseMutationResult<
  CodeSentResponse,
  ApiError,
  EmailOnlyPayload
> {
  return useMutation<CodeSentResponse, ApiError, EmailOnlyPayload>({
    mutationFn: requestPasswordReset,
  });
}

/** Replace a forgotten password with the emailed code (Requirement 3.2). */
export function useResetPassword(): UseMutationResult<void, ApiError, PasswordResetPayload> {
  return useMutation<void, ApiError, PasswordResetPayload>({
    mutationFn: resetPassword,
  });
}

// ---------------------------------------------------------------------------
// The signed-in account's own credentials (email-auth Requirement 4)
// ---------------------------------------------------------------------------

/** Query keys for the signed-in account; keyed by user so a new sign-in never reads the last one's. */
export const authKeys = {
  me: (userId: string | undefined) => ['auth', 'me', userId ?? 'anonymous'] as const,
};

/** The signed-in account's email, mobile and whether it has a password. */
export function useAccountCredentials(): UseQueryResult<AccountCredentials, ApiError> {
  const userId = useAuthStore((state) => state.user?.id);
  return useQuery<AccountCredentials, ApiError>({
    queryKey: authKeys.me(userId),
    queryFn: fetchAccountCredentials,
  });
}

/** Email a code to a new address for this account (Requirement 4.1). */
export function useRequestEmailChange(): UseMutationResult<
  CodeSentResponse,
  ApiError,
  EmailChangePayload
> {
  return useMutation<CodeSentResponse, ApiError, EmailChangePayload>({
    mutationFn: requestEmailChange,
  });
}

/**
 * Save the new address once its code is entered. The answer is the updated
 * account, which becomes the cached copy and the session profile's email.
 */
export function useConfirmEmailChange(): UseMutationResult<
  AccountCredentials,
  ApiError,
  { code: string }
> {
  const queryClient = useQueryClient();
  const userId = useAuthStore((state) => state.user?.id);
  const updateProfile = useAuthStore((state) => state.updateProfile);

  return useMutation<AccountCredentials, ApiError, { code: string }>({
    mutationFn: confirmEmailChange,
    onSuccess: (credentials) => {
      queryClient.setQueryData(authKeys.me(userId), credentials);
      if (credentials.email) {
        updateProfile({ email: credentials.email });
      }
    },
  });
}

/** Set or change the password, then re-read the account (it now has one). */
export function useChangePassword(): UseMutationResult<void, ApiError, PasswordChangePayload> {
  const queryClient = useQueryClient();
  const userId = useAuthStore((state) => state.user?.id);

  return useMutation<void, ApiError, PasswordChangePayload>({
    mutationFn: changePassword,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: authKeys.me(userId) });
    },
  });
}
