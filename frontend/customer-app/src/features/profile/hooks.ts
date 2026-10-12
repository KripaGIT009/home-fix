import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { UseMutationResult, UseQueryResult } from '@tanstack/react-query';
import type { ApiError } from '@api/client';
import type { CodeSentResponse } from '@features/auth/api';
import { useAuthStore, type UserProfile } from '@stores/authStore';
import {
  changePassword,
  confirmEmailChange,
  fetchAccount,
  requestEmailChange,
  type AccountCredentials,
  type EmailChangeCodePayload,
  type EmailChangePayload,
  type PasswordChangePayload,
} from './api';

/** TanStack Query hooks for the profile's "Email & password" (email-auth Requirement 4). */

export const accountKeys = {
  /** Keyed by account so a different sign-in on this device never sees stale details. */
  me: (userId: string) => ['account', 'me', userId] as const,
};

/**
 * Copy what the Auth Service knows about the person into the stored profile.
 * Sign-in responses carry no name, email or mobile, so this is where the
 * profile header learns them.
 */
function syncProfile(account: AccountCredentials): void {
  const patch: Partial<UserProfile> = {};
  if (account.displayName) patch.displayName = account.displayName;
  if (account.email) patch.email = account.email;
  if (account.mobileNumber) patch.mobileNumber = account.mobileNumber;
  useAuthStore.getState().updateProfile(patch);
}

/** The signed-in account's sign-in details (GET /auth/me). */
export function useAccount(): UseQueryResult<AccountCredentials, ApiError> {
  const userId = useAuthStore((state) => state.user?.id ?? '');

  return useQuery<AccountCredentials, ApiError>({
    queryKey: accountKeys.me(userId),
    queryFn: async () => {
      const account = await fetchAccount();
      syncProfile(account);
      return account;
    },
    enabled: userId !== '',
  });
}

/** Email a code to a new address (email-auth Requirement 4.1). */
export function useRequestEmailChange(): UseMutationResult<
  CodeSentResponse,
  ApiError,
  EmailChangePayload
> {
  return useMutation<CodeSentResponse, ApiError, EmailChangePayload>({
    mutationFn: requestEmailChange,
  });
}

/** Enter the code; the verified address replaces the account's email. */
export function useConfirmEmailChange(): UseMutationResult<
  AccountCredentials,
  ApiError,
  EmailChangeCodePayload
> {
  const queryClient = useQueryClient();

  return useMutation<AccountCredentials, ApiError, EmailChangeCodePayload>({
    mutationFn: confirmEmailChange,
    onSuccess: (account) => {
      queryClient.setQueryData(accountKeys.me(account.userId), account);
      syncProfile(account);
    },
  });
}

/** Set or change the password (email-auth Requirements 4.1–4.2). */
export function useChangePassword(): UseMutationResult<void, ApiError, PasswordChangePayload> {
  const queryClient = useQueryClient();
  const userId = useAuthStore((state) => state.user?.id ?? '');

  return useMutation<void, ApiError, PasswordChangePayload>({
    mutationFn: changePassword,
    onSuccess: () => {
      queryClient.setQueryData<AccountCredentials>(accountKeys.me(userId), (current) =>
        current ? { ...current, hasPassword: true } : current,
      );
    },
  });
}
