import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { UseMutationResult, UseQueryResult } from '@tanstack/react-query';
import type { ApiError } from '@api/client';
import {
  fetchComplaints,
  updateComplaint,
  type AdminComplaint,
  type ComplaintStatus,
  type ComplaintUpdatePayload,
} from './api';

export const complaintKeys = {
  list: (search: string, status: ComplaintStatus | '') =>
    ['admin', 'complaints', { search, status }] as const,
};

/** Complaints filtered by an optional search term and status (Requirement 19.2). */
export function useComplaints(
  search: string,
  status: ComplaintStatus | '',
): UseQueryResult<AdminComplaint[], ApiError> {
  return useQuery<AdminComplaint[], ApiError>({
    queryKey: complaintKeys.list(search, status),
    queryFn: () => fetchComplaints({ ...(search ? { search } : {}), status }),
  });
}

/** Update a complaint's status / resolution note. */
export function useUpdateComplaint(): UseMutationResult<
  AdminComplaint,
  ApiError,
  { id: string; payload: ComplaintUpdatePayload }
> {
  const queryClient = useQueryClient();
  return useMutation<AdminComplaint, ApiError, { id: string; payload: ComplaintUpdatePayload }>({
    mutationFn: ({ id, payload }) => updateComplaint(id, payload),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['admin', 'complaints'] });
    },
  });
}
