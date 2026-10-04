import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { UseMutationResult, UseQueryResult } from '@tanstack/react-query';
import { isApiError, type ApiError } from '@api/client';
import { fetchActiveCategorySummaries, type CategorySummary } from '@features/categories/api';
import type { TenantPayload } from '@features/tenants/api';
import { useAuthStore } from '@stores/authStore';
import {
  APPLICATION_NOT_FOUND_CODE,
  applyForAgency,
  fetchMyApplication,
  type TenantApplication,
} from './api';

export const agencyKeys = {
  /** Per account: a sign-out and a different sign-in must not see the last one's. */
  mine: (userId: string | undefined) => ['agency', 'application', userId] as const,
  categories: ['agency', 'catalog', 'active-categories'] as const,
};

/**
 * The signed-in account's agency application, or null when it never applied
 * (email-auth Requirement 5.5). The 404 is the normal answer for a new
 * applicant, so it is turned into null rather than surfaced as an error.
 */
export function useMyApplication(): UseQueryResult<TenantApplication | null, ApiError> {
  const userId = useAuthStore((state) => state.user?.id);
  return useQuery<TenantApplication | null, ApiError>({
    queryKey: agencyKeys.mine(userId),
    queryFn: async () => {
      try {
        return await fetchMyApplication();
      } catch (error) {
        if (isApiError(error) && error.code === APPLICATION_NOT_FOUND_CODE) return null;
        throw error;
      }
    },
  });
}

/** Active catalog categories an applicant may cover (email-auth Requirement 5.1). */
export function useActiveCategories(): UseQueryResult<CategorySummary[], ApiError> {
  return useQuery<CategorySummary[], ApiError>({
    queryKey: agencyKeys.categories,
    queryFn: fetchActiveCategorySummaries,
    staleTime: 5 * 60_000,
  });
}

/** Apply for an agency; the answer becomes the account's application at once. */
export function useApplyForAgency(): UseMutationResult<TenantApplication, ApiError, TenantPayload> {
  const queryClient = useQueryClient();
  const userId = useAuthStore((state) => state.user?.id);
  return useMutation<TenantApplication, ApiError, TenantPayload>({
    mutationFn: applyForAgency,
    onSuccess: (application) => {
      queryClient.setQueryData(agencyKeys.mine(userId), application);
    },
  });
}
