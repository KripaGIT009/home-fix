import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { UseMutationResult, UseQueryResult } from '@tanstack/react-query';
import type { ApiError } from '@api/client';
import {
  fetchReviews,
  moderateReview,
  type AdminReview,
  type ModeratePayload,
  type ModerationStatus,
} from './api';

export const reviewKeys = {
  list: (status: ModerationStatus | '') => ['admin', 'reviews', { status }] as const,
};

/** Reviews filtered by an optional moderation status (Requirement 19.2). */
export function useReviews(status: ModerationStatus | ''): UseQueryResult<AdminReview[], ApiError> {
  return useQuery<AdminReview[], ApiError>({
    queryKey: reviewKeys.list(status),
    queryFn: () => fetchReviews(status),
  });
}

/** Publish or remove a review. */
export function useModerateReview(): UseMutationResult<
  AdminReview,
  ApiError,
  { id: string; payload: ModeratePayload }
> {
  const queryClient = useQueryClient();
  return useMutation<AdminReview, ApiError, { id: string; payload: ModeratePayload }>({
    mutationFn: ({ id, payload }) => moderateReview(id, payload),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['admin', 'reviews'] });
    },
  });
}
