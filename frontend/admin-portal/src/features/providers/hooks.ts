import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { UseMutationResult, UseQueryResult } from '@tanstack/react-query';
import type { ApiError } from '@api/client';
import { fetchProviders, updateProviderStatus, type AdminProvider } from './api';

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
  { id: string; status: AdminProvider['status'] }
> {
  const queryClient = useQueryClient();
  return useMutation<AdminProvider, ApiError, { id: string; status: AdminProvider['status'] }>({
    mutationFn: ({ id, status }) => updateProviderStatus(id, status),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['admin', 'providers'] });
    },
  });
}
