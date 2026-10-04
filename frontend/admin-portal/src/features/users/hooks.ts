import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { UseMutationResult, UseQueryResult } from '@tanstack/react-query';
import type { ApiError } from '@api/client';
import {
  createInvitation,
  fetchInvitations,
  fetchUsers,
  revokeInvitation,
  updateUserStatus,
  type AdminUser,
  type InvitationPayload,
  type StaffInvitation,
  type UserStatus,
} from './api';

export const userKeys = {
  list: (search: string) => ['admin', 'users', { search }] as const,
  invitations: ['admin', 'invitations'] as const,
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

/** Open staff invitations (email-auth Requirement 6.5). */
export function useInvitations(): UseQueryResult<StaffInvitation[], ApiError> {
  return useQuery<StaffInvitation[], ApiError>({
    queryKey: userKeys.invitations,
    queryFn: fetchInvitations,
  });
}

/** Invite an email with a staff role (Requirement 6.1). */
export function useCreateInvitation(): UseMutationResult<
  StaffInvitation,
  ApiError,
  InvitationPayload
> {
  const queryClient = useQueryClient();
  return useMutation<StaffInvitation, ApiError, InvitationPayload>({
    mutationFn: createInvitation,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: userKeys.invitations });
    },
  });
}

/** Revoke an open invitation (Requirement 6.5). */
export function useRevokeInvitation(): UseMutationResult<void, ApiError, string> {
  const queryClient = useQueryClient();
  return useMutation<void, ApiError, string>({
    mutationFn: revokeInvitation,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: userKeys.invitations });
    },
  });
}
