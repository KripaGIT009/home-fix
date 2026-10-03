import { useEffect, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { UseMutationResult, UseQueryResult } from '@tanstack/react-query';
import type { ApiError } from '@api/client';
import type { BookingStatus } from '@features/bookings/api';
import type { Tenant, TeamProvider } from '@features/tenants/model';
import { TENANT_ROLES, hasAnyRole } from '@config/roles';
import { useAuthStore } from '@stores/authStore';
import {
  addTeamProvider,
  assignBooking,
  fetchMyTenant,
  fetchQueue,
  fetchTeam,
  fetchTenantBookings,
  removeTeamProvider,
  type TenantBooking,
} from './api';

/**
 * How often the queue is refreshed (Requirement MT-11.2: at least every 15 s).
 * There are no push notifications to Tenant_Admins in this release, so polling
 * is how a new request reaches the portal.
 */
export const QUEUE_POLL_MS = 15_000;

export const tenantPortalKeys = {
  all: ['tenant'] as const,
  me: ['tenant', 'me'] as const,
  team: ['tenant', 'team'] as const,
  queue: ['tenant', 'queue'] as const,
  bookings: (status: BookingStatus | '') => ['tenant', 'bookings', { status }] as const,
};

/** True when the signed-in user works a Tenant's portal. */
function useIsTenantAdmin(): boolean {
  const roles = useAuthStore((state) => state.user?.roles);
  return hasAnyRole(roles ?? [], TENANT_ROLES);
}

/** The caller's own Tenant (name and status for the shell). */
export function useMyTenant(): UseQueryResult<Tenant, ApiError> {
  const enabled = useIsTenantAdmin();
  return useQuery<Tenant, ApiError>({
    queryKey: tenantPortalKeys.me,
    queryFn: fetchMyTenant,
    enabled,
    staleTime: 5 * 60_000,
  });
}

/**
 * The Assignment_Queue, polled every 15 s. The sidebar badge and the Requests
 * screen share this one query, so the count in the navigation and the rows on
 * screen never disagree, and the poll keeps running on the Team and Jobs
 * screens too — a new request is noticed wherever the admin is. It keeps
 * polling in a background tab as well: "at least every 15 seconds" is the
 * requirement, and refetch-on-focus is switched off portal-wide.
 */
export function useAssignmentQueue(): UseQueryResult<TenantBooking[], ApiError> {
  const enabled = useIsTenantAdmin();
  return useQuery<TenantBooking[], ApiError>({
    queryKey: tenantPortalKeys.queue,
    queryFn: fetchQueue,
    enabled,
    staleTime: 0,
    refetchInterval: QUEUE_POLL_MS,
    refetchIntervalInBackground: true,
  });
}

/** Number of waiting requests for the sidebar badge; null until known. */
export function useQueueCount(): number | null {
  const queue = useAssignmentQueue();
  return queue.data ? queue.data.length : null;
}

/** The Tenant's team with availability and assignability (Requirement MT-8.4). */
export function useTeam(
  options: { poll?: boolean } = {},
): UseQueryResult<TeamProvider[], ApiError> {
  return useQuery<TeamProvider[], ApiError>({
    queryKey: tenantPortalKeys.team,
    queryFn: fetchTeam,
    // Beside the queue, availability now is what the admin picks by, so it is
    // refreshed with the queue rather than left 30 s stale.
    ...(options.poll ? { refetchInterval: QUEUE_POLL_MS, staleTime: 0 } : {}),
  });
}

/** The Tenant's bookings, newest first, optionally by status (Requirement MT-8.3). */
export function useTenantBookings(
  status: BookingStatus | '',
): UseQueryResult<TenantBooking[], ApiError> {
  return useQuery<TenantBooking[], ApiError>({
    queryKey: tenantPortalKeys.bookings(status),
    queryFn: () => fetchTenantBookings(status),
  });
}

/**
 * Assign a queued booking to a team member (Requirement MT-5.2). Settled either
 * way, the queue and the Tenant's bookings are refreshed: on success the row
 * has left the queue, and on a lost race (409 BOOKING_NOT_ASSIGNABLE) it has
 * too — Requirement MT-11.3 asks for exactly that refresh.
 */
export function useAssignBooking(): UseMutationResult<
  TenantBooking,
  ApiError,
  { bookingId: string; providerId: string }
> {
  const queryClient = useQueryClient();
  return useMutation<TenantBooking, ApiError, { bookingId: string; providerId: string }>({
    mutationFn: ({ bookingId, providerId }) => assignBooking(bookingId, providerId),
    onSettled: () => {
      void queryClient.invalidateQueries({ queryKey: tenantPortalKeys.queue });
      void queryClient.invalidateQueries({ queryKey: ['tenant', 'bookings'] });
    },
  });
}

export function useAddTeamProvider(): UseMutationResult<TeamProvider, ApiError, string> {
  const queryClient = useQueryClient();
  return useMutation<TeamProvider, ApiError, string>({
    mutationFn: addTeamProvider,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: tenantPortalKeys.team });
    },
  });
}

export function useRemoveTeamProvider(): UseMutationResult<void, ApiError, string> {
  const queryClient = useQueryClient();
  return useMutation<void, ApiError, string>({
    mutationFn: removeTeamProvider,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: tenantPortalKeys.team });
    },
  });
}

/**
 * The current time, re-read every `intervalMs`, so "waiting 12 min" keeps
 * counting between polls (a poll that returns the same rows does not re-render).
 */
export function useNow(intervalMs = 30_000): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const id = window.setInterval(() => setNow(Date.now()), intervalMs);
    return () => window.clearInterval(id);
  }, [intervalMs]);
  return now;
}
