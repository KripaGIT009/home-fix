import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { UseMutationResult, UseQueryResult } from '@tanstack/react-query';
import type { ApiError } from '@api/client';
import {
  fetchBackgroundChecks,
  fetchVerificationDocuments,
  fetchVerificationQueue,
  submitBackgroundCheck,
  submitVerificationDecision,
  type BackgroundCheckEntry,
  type BackgroundCheckPayload,
  type DecisionPayload,
  type VerificationDocument,
  type VerificationQueueEntry,
} from './api';

export const verificationKeys = {
  queue: ['admin', 'verification', 'queue'] as const,
  backgroundChecks: ['admin', 'verification', 'background-checks'] as const,
  documents: (providerId: string) => ['admin', 'verification', 'documents', providerId] as const,
};

/** Providers in DOCUMENT_SUBMITTED status, oldest-first (Requirement 19.3). */
export function useVerificationQueue(): UseQueryResult<VerificationQueueEntry[], ApiError> {
  return useQuery<VerificationQueueEntry[], ApiError>({
    queryKey: verificationKeys.queue,
    queryFn: fetchVerificationQueue,
  });
}

/** A provider's submitted documents for inline viewing (Requirement 19.3). */
export function useVerificationDocuments(
  providerId: string | null,
): UseQueryResult<VerificationDocument[], ApiError> {
  return useQuery<VerificationDocument[], ApiError>({
    queryKey: verificationKeys.documents(providerId ?? ''),
    queryFn: () => fetchVerificationDocuments(providerId as string),
    enabled: Boolean(providerId),
  });
}

/** Approve or reject a provider's verification submission. */
export function useVerificationDecision(
  providerId: string,
): UseMutationResult<void, ApiError, DecisionPayload> {
  const queryClient = useQueryClient();
  return useMutation<void, ApiError, DecisionPayload>({
    mutationFn: (payload) => submitVerificationDecision(providerId, payload),
    onSuccess: () => {
      // A decision removes the provider from the queue; accepting the documents
      // moves them to the background-check step. Refetch both.
      void queryClient.invalidateQueries({ queryKey: verificationKeys.queue });
      void queryClient.invalidateQueries({ queryKey: verificationKeys.backgroundChecks });
    },
  });
}

/** Providers at the background-check step, oldest check first. */
export function useBackgroundChecks(): UseQueryResult<BackgroundCheckEntry[], ApiError> {
  return useQuery<BackgroundCheckEntry[], ApiError>({
    queryKey: verificationKeys.backgroundChecks,
    queryFn: fetchBackgroundChecks,
  });
}

/** Record a provider's background-check result and approve or reject them. */
export function useBackgroundCheckDecision(
  providerId: string,
): UseMutationResult<void, ApiError, BackgroundCheckPayload> {
  const queryClient = useQueryClient();
  return useMutation<void, ApiError, BackgroundCheckPayload>({
    mutationFn: (payload) => submitBackgroundCheck(providerId, payload),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: verificationKeys.backgroundChecks });
    },
  });
}
