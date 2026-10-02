import { useEffect, useMemo, useRef, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import type { UseQueryResult } from '@tanstack/react-query';
import type { ApiError } from '@api/client';
import { useAuthStore } from '@stores/authStore';
import { buildSseUrl } from '@lib/realtime';
import {
  fetchAvailableProfessionals,
  fetchChatChannel,
  fetchChatHistory,
  fetchLocationSnapshot,
  toLocationSnapshot,
  type AvailableProfessional,
  type ChatChannel,
  type ChatMessage,
  type LocationSnapshot,
} from './api';

/**
 * TanStack Query hooks for the tracking flow, plus the live-location bridge
 * that folds Server-Sent Events into the query cache (Requirements 8, 10, 18).
 */

/**
 * How often the location snapshot is re-read while the stream is not
 * connected, including before the provider's first fix (a 404).
 */
const NO_FIX_POLL_MS = 5_000;

export const trackingKeys = {
  professionals: (bookingId: string) => ['tracking', 'professionals', bookingId] as const,
  location: (bookingId: string) => ['tracking', 'location', bookingId] as const,
  chatChannel: (bookingId: string) => ['tracking', 'chat', 'channel', bookingId] as const,
  chatHistory: (channelId: string) => ['tracking', 'chat', 'history', channelId] as const,
};

/** List of eligible Providers for a Booking (Requirement 28.6). */
export function useAvailableProfessionals(
  bookingId: string,
): UseQueryResult<AvailableProfessional[], ApiError> {
  return useQuery<AvailableProfessional[], ApiError>({
    queryKey: trackingKeys.professionals(bookingId),
    queryFn: () => fetchAvailableProfessionals(bookingId),
    enabled: Boolean(bookingId),
    // Availability, distance and ETA drift quickly; keep this fresh.
    staleTime: 15_000,
    refetchInterval: 30_000,
  });
}

/**
 * Live location for a Booking (Requirement 10).
 *
 * The initial coordinates come from a TanStack Query fetch of the Location
 * Service snapshot (delivered within 2 s — Requirement 10.3). A Server-Sent
 * Events subscription then streams subsequent updates, and each event writes
 * directly into the same query cache entry via `setQueryData`, so consumers
 * read a single source of truth and re-render on every push (within 5 s of the
 * Provider moving — Task 33 acceptance).
 *
 * `connected` reflects the SSE transport state so the UI can show a live vs.
 * reconnecting indicator; staleness (Requirement 10.7) is derived by the screen
 * from `snapshot.updatedAt`.
 *
 * Only subscribe while the booking is in a state where the provider shares
 * their location (`enabled`). A 404 from the snapshot endpoint means "no
 * location yet" — the provider has not sent one — and is reported as
 * `awaitingFirstFix`, not as an error. The snapshot is polled for as long as
 * the stream is not connected, so the map moves even when SSE cannot be used.
 */
export function useLiveLocation(
  bookingId: string,
  enabled = true,
): {
  snapshot: LocationSnapshot | undefined;
  isLoading: boolean;
  isError: boolean;
  /** True while no location has been reported for this booking yet. */
  awaitingFirstFix: boolean;
  error: ApiError | null;
  connected: boolean;
  refetch: () => void;
} {
  const queryClient = useQueryClient();
  // Memoised: `trackingKeys.location()` returns a fresh array on every call, and
  // this key is a dependency of the SSE effect below. The screen re-renders once
  // a second from its staleness ticker, so an unmemoised key tore down and
  // recreated the EventSource on every tick.
  const queryKey = useMemo(() => trackingKeys.location(bookingId), [bookingId]);
  const accessToken = useAuthStore((state) => state.accessToken);
  const [connected, setConnected] = useState(false);

  const active = Boolean(bookingId) && enabled;

  const query = useQuery<LocationSnapshot, ApiError>({
    queryKey,
    queryFn: () => fetchLocationSnapshot(bookingId),
    enabled: active,
    // The SSE stream is authoritative while it is connected. It often is not —
    // the gateway authenticates by header only, which EventSource cannot send —
    // so until it connects the snapshot is polled; that is the delivery path the
    // screen can rely on. Before the first fix exists the poll answers 404.
    staleTime: connected ? Infinity : 0,
    gcTime: 60_000,
    refetchInterval: connected ? false : NO_FIX_POLL_MS,
  });

  useEffect(() => {
    if (!active) return;
    if (typeof EventSource === 'undefined') return;

    const url = buildSseUrl(`/locations/${bookingId}/stream`, accessToken);
    const source = new EventSource(url);

    const handleUpdate = (event: MessageEvent<string>) => {
      try {
        const update = toLocationSnapshot(bookingId, JSON.parse(event.data));
        if (update) {
          queryClient.setQueryData<LocationSnapshot>(queryKey, (prev) => ({ ...prev, ...update }));
        }
      } catch {
        // Ignore malformed frames; the next valid update will refresh the view.
      }
    };

    source.onopen = () => setConnected(true);
    source.onmessage = handleUpdate;
    // The Location Service may use a named event; subscribe to both.
    source.addEventListener('location', handleUpdate as EventListener);
    source.onerror = () => {
      // EventSource auto-reconnects; reflect the transient drop in the UI.
      setConnected(false);
    };

    return () => {
      source.removeEventListener('location', handleUpdate as EventListener);
      source.close();
      setConnected(false);
    };
  }, [active, bookingId, accessToken, queryClient, queryKey]);

  const awaitingFirstFix =
    query.data === undefined && (query.error?.status === 404 || (active && query.isPending));

  return {
    snapshot: query.data,
    isLoading: query.isLoading,
    isError: query.isError && query.error.status !== 404,
    awaitingFirstFix,
    error: query.error?.status === 404 ? null : (query.error ?? null),
    connected,
    refetch: () => void query.refetch(),
  };
}

/** Resolve the chat channel for an active Booking (Requirement 18.1). */
export function useChatChannel(bookingId: string): UseQueryResult<ChatChannel, ApiError> {
  return useQuery<ChatChannel, ApiError>({
    queryKey: trackingKeys.chatChannel(bookingId),
    queryFn: () => fetchChatChannel(bookingId),
    enabled: Boolean(bookingId),
    staleTime: 60_000,
  });
}

/** Chat message history for a channel (Requirement 18.4). */
export function useChatHistory(channelId: string): UseQueryResult<ChatMessage[], ApiError> {
  return useQuery<ChatMessage[], ApiError>({
    queryKey: trackingKeys.chatHistory(channelId),
    queryFn: () => fetchChatHistory(channelId),
    enabled: Boolean(channelId),
    staleTime: 30_000,
  });
}

/**
 * A one-second tick used to keep the "last updated Ns ago" staleness label and
 * the ETA countdown current between stream events (Requirement 10.7).
 */
export function useNowTick(intervalMs: number): number {
  const [now, setNow] = useState(() => Date.now());
  const ref = useRef(intervalMs);
  ref.current = intervalMs;

  useEffect(() => {
    const id = window.setInterval(() => setNow(Date.now()), ref.current);
    return () => window.clearInterval(id);
  }, []);

  return now;
}
