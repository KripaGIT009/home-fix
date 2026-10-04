import { useMutation } from '@tanstack/react-query';
import type { UseMutationResult } from '@tanstack/react-query';
import { useAuthStore } from '@stores/authStore';
import type { ApiError } from '@api/client';
import {
  loginWithPassword,
  requestOtp,
  requestPasswordReset,
  resendSignUpCode,
  resetPassword,
  signUpWithEmail,
  socialLogin,
  toSession,
  verifyEmailSignUp,
  verifyOtp,
  type AuthSessionResponse,
  type CodeSentResponse,
  type EmailCodePayload,
  type EmailOnlyPayload,
  type EmailSignUpPayload,
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

/** Sign up with name, email, mobile and password (email-auth Requirement 1.1). */
export function useEmailSignUp(): UseMutationResult<
  CodeSentResponse,
  ApiError,
  EmailSignUpPayload
> {
  return useMutation<CodeSentResponse, ApiError, EmailSignUpPayload>({
    mutationFn: signUpWithEmail,
  });
}

/**
 * Enter the emailed sign-up code (email-auth Requirement 1.6). The answer is
 * the same session OTP verification yields, stored the same way.
 */
export function useVerifyEmailSignUp(): UseMutationResult<
  AuthSessionResponse,
  ApiError,
  EmailCodePayload
> {
  const setSession = useAuthStore((state) => state.setSession);

  return useMutation<AuthSessionResponse, ApiError, EmailCodePayload>({
    mutationFn: verifyEmailSignUp,
    onSuccess: (response) => {
      const { tokens, user } = toSession(response);
      setSession(tokens, user);
    },
  });
}

/** Email a new sign-up code (email-auth Requirement 1.8). */
export function useResendSignUpCode(): UseMutationResult<
  CodeSentResponse,
  ApiError,
  EmailOnlyPayload
> {
  return useMutation<CodeSentResponse, ApiError, EmailOnlyPayload>({
    mutationFn: resendSignUpCode,
  });
}

/** Sign in with an email and password (email-auth Requirement 2). */
export function usePasswordLogin(): UseMutationResult<
  AuthSessionResponse,
  ApiError,
  PasswordLoginPayload
> {
  const setSession = useAuthStore((state) => state.setSession);

  return useMutation<AuthSessionResponse, ApiError, PasswordLoginPayload>({
    mutationFn: loginWithPassword,
    // Like a social login, the response names no mobile number; the profile
    // screen fills it in from GET /auth/me.
    onSuccess: (response) => {
      const { tokens, user } = toSession(response);
      setSession(tokens, user);
    },
  });
}

/** Email a password-reset code (email-auth Requirement 3.1). */
export function useForgotPassword(): UseMutationResult<
  CodeSentResponse,
  ApiError,
  EmailOnlyPayload
> {
  return useMutation<CodeSentResponse, ApiError, EmailOnlyPayload>({
    mutationFn: requestPasswordReset,
  });
}

/**
 * Set a new password with the reset code (email-auth Requirement 3.2). The
 * service ends every session of the account, so when this device is signed in
 * to that same account its session is dropped too, rather than left to fail on
 * its next refresh.
 */
export function useResetPassword(): UseMutationResult<void, ApiError, PasswordResetPayload> {
  return useMutation<void, ApiError, PasswordResetPayload>({
    mutationFn: resetPassword,
    onSuccess: (_data, variables) => {
      const { user, clearSession } = useAuthStore.getState();
      if (user?.email && user.email.toLowerCase() === variables.email.toLowerCase()) {
        clearSession();
      }
    },
  });
}
