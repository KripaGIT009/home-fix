import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { UseMutationResult, UseQueryResult } from '@tanstack/react-query';
import type { ApiError } from '@api/client';
import { fetchSystemConfig, updateSystemConfig, type SystemSetting } from './api';

export const systemKeys = {
  config: ['admin', 'system-config'] as const,
};

/** All system configuration settings (SUPER_ADMIN only — Requirement 19.6). */
export function useSystemConfig(): UseQueryResult<SystemSetting[], ApiError> {
  return useQuery<SystemSetting[], ApiError>({
    queryKey: systemKeys.config,
    queryFn: fetchSystemConfig,
  });
}

/** Persist updated system settings. */
export function useUpdateSystemConfig(): UseMutationResult<
  SystemSetting[],
  ApiError,
  Record<string, string>
> {
  const queryClient = useQueryClient();
  return useMutation<SystemSetting[], ApiError, Record<string, string>>({
    mutationFn: updateSystemConfig,
    onSuccess: (data) => {
      queryClient.setQueryData(systemKeys.config, data);
    },
  });
}
