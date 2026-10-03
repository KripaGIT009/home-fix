import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { UseMutationResult, UseQueryResult } from '@tanstack/react-query';
import type { ApiError } from '@api/client';
import {
  fetchProviders,
  updateProviderStatus,
  verifyProviderBankAccount,
  type AdminProvider,
  type ProviderBankAccount,
  type ProviderStatusChange,
} from './api';

export const providerKeys = {
  list: (search: string) => ['admin', 'providers', { search }] as const,
};

/** Providers, filtered by an optional search term (Requirement 19.2). */
export function useProviders(search: string): UseQueryResult<AdminProvider[], ApiError> {
  return useQuery<AdminProvider[], ApiError>({
    queryKey: providerKeys.list(search),
    queryFn: () => fetchProviders(search || undefined),
  });
}

/** Suspend or reactivate a provider account. */
export function useUpdateProviderStatus(): UseMutationResult<
  AdminProvider,
  ApiError,
  { id: string; status: ProviderStatusChange }
> {
  const queryClient = useQueryClient();
  return useMutation<AdminProvider, ApiError, { id: string; status: ProviderStatusChange }>({
    mutationFn: ({ id, status }) => updateProviderStatus(id, status),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['admin', 'providers'] });
    },
  });
}

/**
 * Mark a provider's bank account verified. The list is refreshed on failure
 * too: a 404 means the account changed or went away since it was loaded.
 */
export function useVerifyProviderBankAccount(): UseMutationResult<
  ProviderBankAccount,
  ApiError,
  string
> {
  const queryClient = useQueryClient();
  return useMutation<ProviderBankAccount, ApiError, string>({
    mutationFn: verifyProviderBankAccount,
    onSettled: () => {
      void queryClient.invalidateQueries({ queryKey: ['admin', 'providers'] });
    },
  });
}
