import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { UseMutationResult, UseQueryResult } from '@tanstack/react-query';
import type { ApiError } from '@api/client';
import { useAuthStore } from '@stores/authStore';
import {
  fetchVerificationState,
  submitVerificationDocuments,
  type DocumentType,
  type VerificationState,
} from './api';

/** Query keys for the Provider's verification state. */
export const verificationKeys = {
  all: ['provider', 'verification'] as const,
  state: (providerId: string) => ['provider', 'verification', providerId] as const,
};

/** The signed-in provider's id: verification records are keyed by the user id. */
function useMyUserId(): string {
  return useAuthStore((state) => state.user?.id) ?? '';
}

/** Current verification status + document checklist (Requirement 5.11). */
export function useVerificationState(): UseQueryResult<VerificationState, ApiError> {
  const providerId = useMyUserId();
  return useQuery<VerificationState, ApiError>({
    queryKey: verificationKeys.state(providerId),
    queryFn: () => fetchVerificationState(providerId),
    enabled: providerId !== '',
  });
}

/** Submit all required documents, then show the updated status (Requirement 5.3). */
export function useSubmitVerificationDocuments(): UseMutationResult<
  VerificationState,
  ApiError,
  Record<DocumentType, File>
> {
  const providerId = useMyUserId();
  const queryClient = useQueryClient();
  return useMutation<VerificationState, ApiError, Record<DocumentType, File>>({
    mutationFn: (files) => submitVerificationDocuments(providerId, files),
    onSuccess: (state) => {
      queryClient.setQueryData(verificationKeys.state(providerId), state);
      void queryClient.invalidateQueries({ queryKey: verificationKeys.all });
    },
  });
}
