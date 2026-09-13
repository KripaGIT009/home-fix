import { useMutation } from '@tanstack/react-query';
import type { UseMutationResult } from '@tanstack/react-query';
import { useAuthStore } from '@stores/authStore';
import { ApiError } from '@api/client';
import { isStaff } from '@config/roles';
import {
  passwordLogin,
  requestOtp,
  socialLogin,
  toSession,
  verifyOtp,
  type AuthSessionResponse,
  type RequestOtpPayload,
  type RequestOtpResponse,
  type PasswordLoginPayload,
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

/**
 * Reject a session that does not belong in the Admin Portal.
 *
 * The Auth Service happily issues tokens to customers and providers from the
 * same OTP endpoints, so a valid session is not evidence of staff access. The
 * check lives in the mutation function, before any session is stored, so a
 * non-staff sign-in surfaces as a failed login instead of dropping the user
 * inside the admin shell with nothing they may open.
 */
function assertStaffSession(response: AuthSessionResponse): AuthSessionResponse {
  if (!isStaff(response.roles)) {
    throw new ApiError({
      status: 403,
      code: 'NOT_STAFF',
      message: 'This account does not have Admin Portal access.',
    });
  }
  return response;
}

/** Verify an OTP and establish a session (Requirement 1.2). */
export function useVerifyOtp(): UseMutationResult<AuthSessionResponse, ApiError, VerifyOtpPayload> {
  const setSession = useAuthStore((state) => state.setSession);

  return useMutation<AuthSessionResponse, ApiError, VerifyOtpPayload>({
    mutationFn: async (payload) => assertStaffSession(await verifyOtp(payload)),
    // The response carries no mobile number, so the one just verified (already
    // E.164 from the login screen) is threaded through into the profile.
    onSuccess: (response, variables) => {
      const { tokens, user } = toSession(response, variables.mobileNumber);
      setSession(tokens, user);
    },
  });
}

/**
 * Sign in with a username and password — the staff path into the console.
 *
 * Runs the same staff assertion as the OTP flow. The password endpoint issues
 * tokens for any account that holds credentials, customers included, so a
 * successful authentication is still not evidence of Admin Portal access.
 */
export function usePasswordLogin(): UseMutationResult<
  AuthSessionResponse,
  ApiError,
  PasswordLoginPayload
> {
  const setSession = useAuthStore((state) => state.setSession);

  return useMutation<AuthSessionResponse, ApiError, PasswordLoginPayload>({
    mutationFn: async (payload) => assertStaffSession(await passwordLogin(payload)),
    // The response carries no mobile number and the form collected none, so the
    // profile records the username the operator actually signed in with.
    onSuccess: (response, variables) => {
      const { tokens, user } = toSession(response);
      setSession(tokens, { ...user, displayName: variables.username });
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
    mutationFn: async (payload) => assertStaffSession(await socialLogin(payload)),
    // No mobile number is involved in a social login; the profile goes without
    // one until a profile fetch supplies it.
    onSuccess: (response) => {
      const { tokens, user } = toSession(response);
      setSession(tokens, user);
    },
  });
}
