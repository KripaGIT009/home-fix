import { useMutation } from '@tanstack/react-query';
import type { UseMutationResult } from '@tanstack/react-query';
import { useAuthStore } from '@stores/authStore';
import type { ApiError } from '@api/client';
import {
  requestOtp,
  socialLogin,
  toSession,
  verifyOtp,
  type AuthSessionResponse,
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
