import { useMutation, useQuery } from '@tanstack/react-query';
import type { UseMutationResult, UseQueryResult } from '@tanstack/react-query';
import { useAuthStore } from '@stores/authStore';
import type { ApiError } from '@api/client';
import {
  acceptInvitation,
  fetchInvitation,
  forgotPassword,
  passwordLogin,
  registerWithEmail,
  requestOtp,
  resendEmailCode,
  resetPassword,
  socialLogin,
  toSession,
  verifyEmailSignup,
  verifyOtp,
  type AuthSessionResponse,
  type CodeSentResponse,
  type EmailCodePayload,
  type EmailSignupPayload,
  type InvitationAcceptancePayload,
  type InvitationPreview,
  type PasswordResetPayload,
  type RequestOtpPayload,
  type RequestOtpResponse,
  type PasswordLoginPayload,
  type SocialLoginPayload,
  type VerifyOtpPayload,
} from './api';

/**
 * TanStack Query mutations for the sign-in flows (Requirement 1, email-auth
 * Requirements 1-3 and 6). Mutations never retry (client-error semantics live
 * in queryClient), and every hook that yields a session persists it into the
 * auth store on success.
 *
 * No hook refuses a session for lacking a staff role. A customer account that
 * signs in here is an agency applicant (email-auth Requirement 5.5): the route
 * guards send it to its application status page rather than into the shell, so
 * a valid non-staff session is no longer a failed login.
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

/**
 * Sign in with an email or username and a password — the staff path into the
 * console, and an agency applicant's. What the session may open is decided by
 * its roles, at the route guards.
 */
export function usePasswordLogin(): UseMutationResult<
  AuthSessionResponse,
  ApiError,
  PasswordLoginPayload
> {
  const setSession = useAuthStore((state) => state.setSession);

  return useMutation<AuthSessionResponse, ApiError, PasswordLoginPayload>({
    mutationFn: passwordLogin,
    // The response carries no profile, so the profile records the identifier the
    // operator actually signed in with (and keeps it as the email when it is one).
    onSuccess: (response, variables) => {
      const { tokens, user } = toSession(response);
      const identifier = variables.identifier.trim();
      setSession(tokens, {
        ...user,
        displayName: identifier,
        ...(identifier.includes('@') ? { email: identifier.toLowerCase() } : {}),
      });
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

/**
 * Create an email sign-up (email-auth Requirement 1). Answers 202 whether or not
 * the address already has an account; only the inbox tells the difference.
 */
export function useEmailSignup(): UseMutationResult<
  CodeSentResponse,
  ApiError,
  EmailSignupPayload
> {
  return useMutation<CodeSentResponse, ApiError, EmailSignupPayload>({
    mutationFn: registerWithEmail,
  });
}

/** The sign-up code plus, when the form knows it, the name to show for the account. */
export type VerifyEmailSignupVariables = EmailCodePayload & { displayName?: string };

/** Enter the emailed sign-up code; activates the account and signs it in (Requirement 1.6). */
export function useVerifyEmailSignup(): UseMutationResult<
  AuthSessionResponse,
  ApiError,
  VerifyEmailSignupVariables
> {
  const setSession = useAuthStore((state) => state.setSession);

  return useMutation<AuthSessionResponse, ApiError, VerifyEmailSignupVariables>({
    mutationFn: ({ email, code }) => verifyEmailSignup({ email, code }),
    onSuccess: (response, variables) => {
      const { tokens, user } = toSession(response);
      setSession(tokens, {
        ...user,
        email: variables.email,
        displayName: variables.displayName ?? variables.email,
      });
    },
  });
}

/** Email a new sign-up code (Requirement 1.8: once a minute, five an hour). */
export function useResendEmailCode(): UseMutationResult<CodeSentResponse, ApiError, string> {
  return useMutation<CodeSentResponse, ApiError, string>({ mutationFn: resendEmailCode });
}

/** Ask for a password-reset code (email-auth Requirement 3.1). */
export function useForgotPassword(): UseMutationResult<CodeSentResponse, ApiError, string> {
  return useMutation<CodeSentResponse, ApiError, string>({ mutationFn: forgotPassword });
}

/** Set a new password with the emailed code (Requirement 3.2); signs nobody in. */
export function useResetPassword(): UseMutationResult<void, ApiError, PasswordResetPayload> {
  return useMutation<void, ApiError, PasswordResetPayload>({ mutationFn: resetPassword });
}

/** What a staff invitation link offers (email-auth Requirement 6.3). */
export function useInvitation(token: string): UseQueryResult<InvitationPreview, ApiError> {
  return useQuery<InvitationPreview, ApiError>({
    queryKey: ['auth', 'invitation', token],
    queryFn: () => fetchInvitation(token),
    // The preview is a one-off read of a single-use link; refetching it after
    // acceptance would only answer 410 over the success state.
    staleTime: Infinity,
  });
}

/** What the acceptance form sends, plus the invitee's email for the profile. */
export interface AcceptInvitationVariables {
  token: string;
  email: string;
  payload: InvitationAcceptancePayload;
}

/** Accept a staff invitation; signs the invitee in with the role (Requirements 6.3, 6.4). */
export function useAcceptInvitation(): UseMutationResult<
  AuthSessionResponse,
  ApiError,
  AcceptInvitationVariables
> {
  const setSession = useAuthStore((state) => state.setSession);

  return useMutation<AuthSessionResponse, ApiError, AcceptInvitationVariables>({
    mutationFn: ({ token, payload }) => acceptInvitation(token, payload),
    onSuccess: (response, { email, payload }) => {
      const mobileNumber = 'mobileNumber' in payload ? payload.mobileNumber : undefined;
      const { tokens, user } = toSession(response, mobileNumber);
      setSession(tokens, {
        ...user,
        email,
        displayName: 'displayName' in payload ? payload.displayName : email,
      });
    },
  });
}
