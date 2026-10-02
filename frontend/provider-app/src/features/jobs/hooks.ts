import { useEffect, useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { UseMutationResult, UseQueryResult } from '@tanstack/react-query';
import type { ApiError } from '@api/client';
import { dashboardKeys } from '../dashboard/hooks';
import {
  acceptJobOffer,
  addJobPart,
  completeJob,
  declineJobOffer,
  fetchJobCompletionSummary,
  fetchJobDetail,
  fetchJobOffer,
  fetchJobOffers,
  markArrived,
  markOnTheWay,
  pauseJob,
  postLocation,
  resumeJob,
  startJob,
  uploadJobPhotos,
  type AddPartPayload,
  type JobCompletionSummary,
  type JobDetail,
  type JobOffer,
  type PhotoKind,
} from './api';

/** Query keys for Provider job-execution resources. */
export const jobKeys = {
  offers: ['provider', 'job-offers'] as const,
  offer: (bookingId: string) => ['provider', 'job-offer', bookingId] as const,
  detail: (bookingId: string) => ['provider', 'job', bookingId] as const,
  summary: (bookingId: string) => ['provider', 'job-summary', bookingId] as const,
};

/** How often the dashboard asks for new offers while it is open. */
export const PENDING_OFFERS_POLL_MS = 5_000;

/**
 * The provider's open job offers, polled every 5 s (Requirement 28.8).
 *
 * Polling is the delivery path the app can rely on: the push sent when an
 * offer is made is best-effort, and an offer window is short, so a missed push
 * must not mean a missed job.
 */
export function usePendingJobOffers(): UseQueryResult<JobOffer[], ApiError> {
  return useQuery<JobOffer[], ApiError>({
    queryKey: jobKeys.offers,
    queryFn: fetchJobOffers,
    refetchInterval: PENDING_OFFERS_POLL_MS,
    // Each poll replaces the list; a cached list would show offers that have lapsed.
    staleTime: 0,
  });
}

/** Offer detail for the Job Request screen (Requirement 28.8). */
export function useJobOffer(bookingId: string): UseQueryResult<JobOffer, ApiError> {
  return useQuery<JobOffer, ApiError>({
    queryKey: jobKeys.offer(bookingId),
    queryFn: () => fetchJobOffer(bookingId),
    // The response window is short-lived; don't refetch and reset the timer.
    staleTime: Infinity,
    gcTime: 0,
    retry: false,
  });
}

/** How often an open job is re-read while it waits on the customer's quote decision. */
export const APPROVAL_POLL_MS = 5_000;

/**
 * Full job detail (Requirement 11.1). While the customer is deciding on a
 * parts quote the decision arrives from their side, so the detail is polled
 * until it moves on (Requirement 9.7-9.9).
 */
export function useJobDetail(bookingId: string): UseQueryResult<JobDetail, ApiError> {
  return useQuery<JobDetail, ApiError>({
    queryKey: jobKeys.detail(bookingId),
    queryFn: () => fetchJobDetail(bookingId),
    refetchInterval: (query) =>
      query.state.data?.status === 'CUSTOMER_APPROVAL_PENDING' ? APPROVAL_POLL_MS : false,
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
 * Shared success handlers. A transition answers with the bare booking, not the
 * job detail, so `onTransition` refetches the detail (and the dashboard's
 * active-job list, which shows the status) rather than patching the cache.
 */
function useJobMutationHelpers(bookingId: string) {
  const queryClient = useQueryClient();
  return {
    onTransition: () => {
      void queryClient.invalidateQueries({ queryKey: jobKeys.detail(bookingId) });
      void queryClient.invalidateQueries({ queryKey: dashboardKeys.activeJobs });
    },
    invalidateDetail: () =>
      void queryClient.invalidateQueries({ queryKey: jobKeys.detail(bookingId) }),
    invalidateActiveJobs: () =>
      void queryClient.invalidateQueries({ queryKey: dashboardKeys.activeJobs }),
  };
}

/**
 * Shared settle handler for an offer decision. Whatever the outcome, the
 * offer's state has moved on (decided, or refused because it had expired or
 * was already decided), so the offers list and this offer's detail are
 * refetched rather than patched. An accept also invalidates the active-job
 * list: the job itself is not written into the cache here, because it becomes
 * the provider's booking only once the Dispatch Engine has confirmed the accept
 * with the Booking Service, a moment later.
 */
function useOfferDecisionSettled(bookingId: string) {
  const queryClient = useQueryClient();
  return () => {
    void queryClient.invalidateQueries({ queryKey: jobKeys.offers });
    void queryClient.invalidateQueries({ queryKey: jobKeys.offer(bookingId) });
    void queryClient.invalidateQueries({ queryKey: dashboardKeys.activeJobs });
  };
}

/** Accept a job offer (Requirement 8.6). */
export function useAcceptJobOffer(bookingId: string): UseMutationResult<JobOffer, ApiError, void> {
  const onSettled = useOfferDecisionSettled(bookingId);
  return useMutation<JobOffer, ApiError, void>({
    mutationFn: () => acceptJobOffer(bookingId),
    onSettled,
  });
}

/** Decline a job offer (Requirement 8.7). */
export function useDeclineJobOffer(bookingId: string): UseMutationResult<JobOffer, ApiError, void> {
  const onSettled = useOfferDecisionSettled(bookingId);
  return useMutation<JobOffer, ApiError, void>({
    mutationFn: () => declineJobOffer(bookingId),
    onSettled,
  });
}

/** Upload before/after photos, then refresh the job detail. */
export function useUploadJobPhotos(
  bookingId: string,
): UseMutationResult<void, ApiError, { kind: PhotoKind; files: File[] }> {
  const { invalidateDetail } = useJobMutationHelpers(bookingId);
  return useMutation<void, ApiError, { kind: PhotoKind; files: File[] }>({
    mutationFn: ({ kind, files }) => uploadJobPhotos(bookingId, kind, files),
    // Refresh even after a partial failure: earlier files in the batch may have landed.
    onSettled: invalidateDetail,
  });
}

/** Transition to PROVIDER_ON_THE_WAY (Requirement 9.3). */
export function useMarkOnTheWay(bookingId: string): UseMutationResult<void, ApiError, void> {
  const { onTransition } = useJobMutationHelpers(bookingId);
  return useMutation<void, ApiError, void>({
    mutationFn: () => markOnTheWay(bookingId),
    onSuccess: onTransition,
  });
}

/** Transition to PROVIDER_ARRIVED (Requirement 9.4). */
export function useMarkArrived(bookingId: string): UseMutationResult<void, ApiError, void> {
  const { onTransition } = useJobMutationHelpers(bookingId);
  return useMutation<void, ApiError, void>({
    mutationFn: () => markArrived(bookingId),
    onSuccess: onTransition,
  });
}

/** Transition to JOB_STARTED (Requirement 9.5, 11.2). */
export function useStartJob(bookingId: string): UseMutationResult<void, ApiError, void> {
  const { onTransition } = useJobMutationHelpers(bookingId);
  return useMutation<void, ApiError, void>({
    mutationFn: () => startJob(bookingId),
    onSuccess: onTransition,
  });
}

/** Pause the job with a mandatory reason (Requirement 11.5). */
export function usePauseJob(bookingId: string): UseMutationResult<void, ApiError, string> {
  const { onTransition } = useJobMutationHelpers(bookingId);
  return useMutation<void, ApiError, string>({
    mutationFn: (reason) => pauseJob(bookingId, reason),
    onSuccess: onTransition,
  });
}

/** Resume a paused job (Requirement 11.5). */
export function useResumeJob(bookingId: string): UseMutationResult<void, ApiError, void> {
  const { onTransition } = useJobMutationHelpers(bookingId);
  return useMutation<void, ApiError, void>({
    mutationFn: () => resumeJob(bookingId),
    onSuccess: onTransition,
  });
}

/** Add a parts/materials line item (Requirement 11.3). */
export function useAddJobPart(
  bookingId: string,
): UseMutationResult<void, ApiError, AddPartPayload> {
  const { onTransition } = useJobMutationHelpers(bookingId);
  return useMutation<void, ApiError, AddPartPayload>({
    mutationFn: (payload) => addJobPart(bookingId, payload),
    // Adding parts moves the job to CUSTOMER_APPROVAL_PENDING.
    onSuccess: onTransition,
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

/** Minimum gap between two location posts; the Location Service refuses more than one per 5 s. */
export const LOCATION_SHARE_INTERVAL_MS = 10_000;

/** Where location sharing stands, for the status line on the Active Job screen. */
export type LocationShareState = 'off' | 'starting' | 'sharing' | 'denied' | 'unavailable';

/**
 * Shares the device's position with the Location Service while `active` — the
 * provider is on the way — so the customer's map moves (Requirement 10.1).
 * Positions come from `watchPosition` and are posted at most every
 * {@link LOCATION_SHARE_INTERVAL_MS}; a failed post is dropped, since the next
 * fix supersedes it.
 */
export function useShareLocation(bookingId: string, active: boolean): LocationShareState {
  const [state, setState] = useState<LocationShareState>('off');
  const lastSentAt = useRef(0);

  useEffect(() => {
    if (!active) {
      setState('off');
      return;
    }
    if (typeof navigator === 'undefined' || !navigator.geolocation) {
      setState('unavailable');
      return;
    }
    setState('starting');
    const watchId = navigator.geolocation.watchPosition(
      (position) => {
        setState('sharing');
        const now = Date.now();
        if (now - lastSentAt.current < LOCATION_SHARE_INTERVAL_MS) return;
        lastSentAt.current = now;
        postLocation(bookingId, position.coords.latitude, position.coords.longitude).catch(() => {
          // Dropped: the next fix is posted in a few seconds.
        });
      },
      (error) => setState(error.code === error.PERMISSION_DENIED ? 'denied' : 'unavailable'),
      { enableHighAccuracy: true, maximumAge: 5_000, timeout: 20_000 },
    );
    return () => navigator.geolocation.clearWatch(watchId);
  }, [bookingId, active]);

  return state;
}
