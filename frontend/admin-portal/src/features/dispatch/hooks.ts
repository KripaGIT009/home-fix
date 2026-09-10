import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { UseMutationResult, UseQueryResult } from '@tanstack/react-query';
import type { ApiError } from '@api/client';
import { fetchDispatchConfig, updateDispatchConfig, type DispatchConfig } from './api';

export const dispatchKeys = {
  config: ['admin', 'dispatch', 'config'] as const,
};

/** Current dispatch configuration (weights + radius parameters). */
export function useDispatchConfig(): UseQueryResult<DispatchConfig, ApiError> {
  return useQuery<DispatchConfig, ApiError>({
    queryKey: dispatchKeys.config,
    queryFn: fetchDispatchConfig,
  });
}

/** Persist updated dispatch configuration (Requirement 19.5). */
export function useUpdateDispatchConfig(): UseMutationResult<
  DispatchConfig,
  ApiError,
  DispatchConfig
> {
  const queryClient = useQueryClient();
  return useMutation<DispatchConfig, ApiError, DispatchConfig>({
    mutationFn: updateDispatchConfig,
    onSuccess: (data) => {
      queryClient.setQueryData(dispatchKeys.config, data);
    },
  });
}
