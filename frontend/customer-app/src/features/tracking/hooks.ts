import { useEffect, useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { UseMutationResult, UseQueryResult } from '@tanstack/react-query';
import type { ApiError } from '@api/client';
import { useAuthStore } from '@stores/authStore';
import { buildSseUrl } from '@lib/realtime';
import {
  fetchAvailableProfessionals,
  fetchChatChannel,
  fetchChatHistory,
  fetchLocationSnapshot,
  selectProfessional,
  type AvailableProfessional,
  type ChatChannel,
  type ChatMessage,
  type LocationSnapshot,
} from './api';

/**
 * TanStack Query hooks for the tracking flow, plus the live-location bridge
 * that folds Server-Sent Events into the query cache (Requirements 8, 10, 18).
 */

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

/** Assign a chosen Provider to the Booking (from the professionals list). */
export function useSelectProfessional(
  bookingId: string,
): UseMutationResult<void, ApiError, string> {
  return useMutation<void, ApiError, string>({
    mutationFn: (providerId: string) => selectProfessional(bookingId, providerId),
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
 */
export function useLiveLocation(bookingId: string): {
  snapshot: LocationSnapshot | undefined;
  isLoading: boolean;
  isError: boolean;
  error: ApiError | null;
  connected: boolean;
  refetch: () => void;
} {
  const queryClient = useQueryClient();
  const queryKey = trackingKeys.location(bookingId);
  const accessToken = useAuthStore((state) => state.accessToken);
  const [connected, setConnected] = useState(false);

  const query = useQuery<LocationSnapshot, ApiError>({
    queryKey,
    queryFn: () => fetchLocationSnapshot(bookingId),
    enabled: Boolean(bookingId),
    // The SSE stream is authoritative once connected; don't background refetch.
    staleTime: Infinity,
    gcTime: 60_000,
  });

  useEffect(() => {
    if (!bookingId) return;
    if (typeof EventSource === 'undefined') return;

    const url = buildSseUrl(`/locations/${bookingId}/stream`, accessToken);
    const source = new EventSource(url);

    const handleUpdate = (event: MessageEvent<string>) => {
      try {
        const update = JSON.parse(event.data) as Partial<LocationSnapshot>;
        queryClient.setQueryData<LocationSnapshot>(queryKey, (prev) => {
          const base: LocationSnapshot = prev ?? {
            bookingId,
            coordinates: update.coordinates ?? { latitude: 0, longitude: 0 },
            etaMinutes: update.etaMinutes ?? 0,
            updatedAt: update.updatedAt ?? new Date().toISOString(),
          };
          return {
            ...base,
            ...update,
            coordinates: update.coordinates ?? base.coordinates,
            updatedAt: update.updatedAt ?? new Date().toISOString(),
          };
        });
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
  }, [bookingId, accessToken, queryClient, queryKey]);

  return {
    snapshot: query.data,
    isLoading: query.isLoading,
    isError: query.isError,
    error: query.error ?? null,
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
