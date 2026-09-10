import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { UseMutationResult, UseQueryResult } from '@tanstack/react-query';
import type { ApiError } from '@api/client';
import { dashboardKeys } from '../dashboard/hooks';
import {
  acceptJob,
  addJobPart,
  completeJob,
  declineJob,
  fetchJobCompletionSummary,
  fetchJobDetail,
  fetchJobOffer,
  pauseJob,
  resumeJob,
  startJob,
  uploadJobPhotos,
  type AddPartPayload,
  type JobCompletionSummary,
  type JobDetail,
  type JobOffer,
  type JobPhoto,
  type PartLineItem,
  type PhotoKind,
} from './api';

/** Query keys for Provider job-execution resources. */
export const jobKeys = {
  offer: (bookingId: string) => ['provider', 'job-offer', bookingId] as const,
  detail: (bookingId: string) => ['provider', 'job', bookingId] as const,
  summary: (bookingId: string) => ['provider', 'job-summary', bookingId] as const,
};

/** Offer detail for the Job Request screen (Requirement 28.8). */
export function useJobOffer(bookingId: string): UseQueryResult<JobOffer, ApiError> {
  return useQuery<JobOffer, ApiError>({
    queryKey: jobKeys.offer(bookingId),
    queryFn: () => fetchJobOffer(bookingId),
    // The 60-second window is short-lived; don't refetch and reset the timer.
    staleTime: Infinity,
    gcTime: 0,
    retry: false,
  });
}

/** Full job detail (Requirement 11.1). */
export function useJobDetail(bookingId: string): UseQueryResult<JobDetail, ApiError> {
  return useQuery<JobDetail, ApiError>({
    queryKey: jobKeys.detail(bookingId),
    queryFn: () => fetchJobDetail(bookingId),
  });
}

/** Completion summary: net duration, parts, final price (Requirement 11.6). */
export function useJobCompletionSummary(
  bookingId: string,
): UseQueryResult<JobCompletionSummary, ApiError> {
  return useQuery<JobCompletionSummary, ApiError>({
    queryKey: jobKeys.summary(bookingId),
    queryFn: () => fetchJobCompletionSummary(bookingId),
  });
}

/**
 * Shared success handler: writes the latest JobDetail into the cache and
 * invalidates the dashboard active-job list so it reflects the new state.
 */
function useJobMutationHelpers(bookingId: string) {
  const queryClient = useQueryClient();
  return {
    onDetail: (detail: JobDetail) => {
      queryClient.setQueryData(jobKeys.detail(bookingId), detail);
      void queryClient.invalidateQueries({ queryKey: dashboardKeys.activeJobs });
    },
    invalidateDetail: () =>
      void queryClient.invalidateQueries({ queryKey: jobKeys.detail(bookingId) }),
    invalidateActiveJobs: () =>
      void queryClient.invalidateQueries({ queryKey: dashboardKeys.activeJobs }),
  };
}

/** Accept a job offer (Requirement 9). */
export function useAcceptJob(bookingId: string): UseMutationResult<JobDetail, ApiError, void> {
  const { onDetail } = useJobMutationHelpers(bookingId);
  return useMutation<JobDetail, ApiError, void>({
    mutationFn: () => acceptJob(bookingId),
    onSuccess: onDetail,
  });
}

/** Decline a job offer (Requirement 9). */
export function useDeclineJob(bookingId: string): UseMutationResult<void, ApiError, void> {
  const { invalidateActiveJobs } = useJobMutationHelpers(bookingId);
  return useMutation<void, ApiError, void>({
    mutationFn: () => declineJob(bookingId),
    onSuccess: invalidateActiveJobs,
  });
}

/** Upload before/after photos, then refresh the job detail. */
export function useUploadJobPhotos(
  bookingId: string,
): UseMutationResult<JobPhoto[], ApiError, { kind: PhotoKind; files: File[] }> {
  const { invalidateDetail } = useJobMutationHelpers(bookingId);
  return useMutation<JobPhoto[], ApiError, { kind: PhotoKind; files: File[] }>({
    mutationFn: ({ kind, files }) => uploadJobPhotos(bookingId, kind, files),
    onSuccess: invalidateDetail,
  });
}

/** Transition to JOB_STARTED (Requirement 9.5, 11.2). */
export function useStartJob(bookingId: string): UseMutationResult<JobDetail, ApiError, void> {
  const { onDetail } = useJobMutationHelpers(bookingId);
  return useMutation<JobDetail, ApiError, void>({
    mutationFn: () => startJob(bookingId),
    onSuccess: onDetail,
  });
}

/** Pause the job with a mandatory reason (Requirement 11.5). */
export function usePauseJob(bookingId: string): UseMutationResult<JobDetail, ApiError, string> {
  const { onDetail } = useJobMutationHelpers(bookingId);
  return useMutation<JobDetail, ApiError, string>({
    mutationFn: (reason) => pauseJob(bookingId, reason),
    onSuccess: onDetail,
  });
}

/** Resume a paused job (Requirement 11.5). */
export function useResumeJob(bookingId: string): UseMutationResult<JobDetail, ApiError, void> {
  const { onDetail } = useJobMutationHelpers(bookingId);
  return useMutation<JobDetail, ApiError, void>({
    mutationFn: () => resumeJob(bookingId),
    onSuccess: onDetail,
  });
}

/** Add a parts/materials line item (Requirement 11.3). */
export function useAddJobPart(
  bookingId: string,
): UseMutationResult<PartLineItem, ApiError, AddPartPayload> {
  const { invalidateDetail } = useJobMutationHelpers(bookingId);
  return useMutation<PartLineItem, ApiError, AddPartPayload>({
    mutationFn: (payload) => addJobPart(bookingId, payload),
    onSuccess: invalidateDetail,
  });
}

/** Complete the job (Requirement 9.10, 11.4, 11.6). */
export function useCompleteJob(
  bookingId: string,
): UseMutationResult<JobCompletionSummary, ApiError, void> {
  const queryClient = useQueryClient();
  const { invalidateDetail, invalidateActiveJobs } = useJobMutationHelpers(bookingId);
  return useMutation<JobCompletionSummary, ApiError, void>({
    mutationFn: () => completeJob(bookingId),
    onSuccess: (summary) => {
      queryClient.setQueryData(jobKeys.summary(bookingId), summary);
      invalidateDetail();
      invalidateActiveJobs();
    },
  });
}
