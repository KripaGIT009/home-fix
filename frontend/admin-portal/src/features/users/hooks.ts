import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { UseMutationResult, UseQueryResult } from '@tanstack/react-query';
import type { ApiError } from '@api/client';
import { fetchUsers, updateUserStatus, type AdminUser, type UserStatus } from './api';

export const userKeys = {
  list: (search: string) => ['admin', 'users', { search }] as const,
};

/** Platform users, filtered by an optional search term (Requirement 19.2). */
export function useUsers(search: string): UseQueryResult<AdminUser[], ApiError> {
  return useQuery<AdminUser[], ApiError>({
    queryKey: userKeys.list(search),
    queryFn: () => fetchUsers(search || undefined),
  });
}

/** Suspend or reactivate a user account. */
export function useUpdateUserStatus(): UseMutationResult<
  AdminUser,
  ApiError,
  { id: string; status: UserStatus }
> {
  const queryClient = useQueryClient();
  return useMutation<AdminUser, ApiError, { id: string; status: UserStatus }>({
    mutationFn: ({ id, status }) => updateUserStatus(id, status),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['admin', 'users'] });
    },
  });
}
