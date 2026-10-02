import { apiClient } from '@api/client';

/**
 * API bindings for the tracking flow: the Available Professionals list
 * (Dispatch/Provider data, Requirement 28.6), the Location Service snapshot
 * (Requirement 10), and the Chat Service channel bootstrap (Requirement 18).
 *
 * Realtime streams (location SSE, chat WebSocket) are handled separately in
 * `realtime.ts`; this module covers the plain REST calls that flow through the
 * shared Axios client (Authorization + X-Correlation-ID interceptors).
 */

/**
 * A candidate Provider for a Booking, shown as a card in the Available
 * Professionals list (Requirement 28.6).
 */
export interface AvailableProfessional {
  providerId: string;
  displayName: string;
  /** True when the Provider's verification status is APPROVED (Requirement 28.6). */
  verified: boolean;
  /** Aggregate star rating (0–5). */
  rating: number;
  /** Total jobs completed. */
  jobsCompleted: number;
  /** Distance from the service address in kilometers. */
  distanceKm: number;
  /** Estimated time of arrival in minutes. */
  etaMinutes: number;
  /** Starting price for the requested service, in the platform currency. */
  startingPrice: number;
  currency?: string;
}

/**
 * GET /bookings/{bookingId}/professionals — ranked list of eligible Providers
 * for a Booking (fed by the Dispatch Engine's matching, Requirement 8/28.6).
 */
export async function fetchAvailableProfessionals(
  bookingId: string,
): Promise<AvailableProfessional[]> {
  const { data } = await apiClient.get<AvailableProfessional[]>(
    `/bookings/${bookingId}/professionals`,
  );
  return data;
}

/** A single geographic coordinate. */
export interface Coordinates {
  latitude: number;
  longitude: number;
}

/**
 * Location snapshot for a Booking's active tracking view (Requirement 10.3,
 * 10.7). The Location Service returns the last known Provider coordinates plus
 * the timestamp of that update so the client can detect staleness.
 */
export interface LocationSnapshot {
  bookingId: string;
  coordinates: Coordinates;
  /**
   * ETA in minutes from the Provider's coordinates to the service address.
   * The Location Service cannot compute one yet (it does not know the
   * address), so the screen estimates it from the booking's coordinates.
   */
  etaMinutes?: number;
  /** ISO-8601 timestamp of the last location update (Requirement 10.7). */
  updatedAt: string;
  /** The Customer's service address coordinates, used to frame the map. */
  destination?: Coordinates;
  provider?: {
    displayName: string;
    verified: boolean;
    rating: number;
  };
}

/**
 * GET /locations/{bookingId} — most recently stored Provider coordinates,
 * delivered within 2 s (Requirement 10.3). Used to seed the tracking map before
 * the realtime stream connects, and as the TanStack Query cache the stream
 * updates.
 */
export async function fetchLocationSnapshot(bookingId: string): Promise<LocationSnapshot> {
  const { data } = await apiClient.get<unknown>(`/locations/${bookingId}`);
  const snapshot = toLocationSnapshot(bookingId, data);
  if (!snapshot) {
    throw new Error('The Location Service answered without coordinates');
  }
  return snapshot;
}

/**
 * Reads a Location Service payload — the snapshot's flat
 * `{latitude, longitude, lastUpdatedAt}` or a stream frame's
 * `{coordinates: {latitude, longitude}}` — into a {@link LocationSnapshot}.
 * The server's `etaMinutes` is dropped: its destination is a placeholder, so
 * the number is meaningless. `null` when the payload carries no position.
 */
export function toLocationSnapshot(bookingId: string, raw: unknown): LocationSnapshot | null {
  const r = (raw ?? {}) as Record<string, unknown>;
  const nested = (r.coordinates ?? {}) as Record<string, unknown>;
  const latitude = typeof r.latitude === 'number' ? r.latitude : nested.latitude;
  const longitude = typeof r.longitude === 'number' ? r.longitude : nested.longitude;
  if (typeof latitude !== 'number' || typeof longitude !== 'number') return null;
  const updatedAt =
    typeof r.lastUpdatedAt === 'string'
      ? r.lastUpdatedAt
      : typeof r.updatedAt === 'string'
        ? r.updatedAt
        : new Date().toISOString();
  return { bookingId, coordinates: { latitude, longitude }, updatedAt };
}

/** Assumed average urban travel speed, the same figure the Location Service uses. */
const AVERAGE_SPEED_KMH = 30;

/** Straight-line travel time between two points at {@link AVERAGE_SPEED_KMH}, in whole minutes. */
export function estimateEtaMinutes(from: Coordinates, to: Coordinates): number {
  const rad = (deg: number) => (deg * Math.PI) / 180;
  const dLat = rad(to.latitude - from.latitude);
  const dLon = rad(to.longitude - from.longitude);
  const h =
    Math.sin(dLat / 2) ** 2 +
    Math.cos(rad(from.latitude)) * Math.cos(rad(to.latitude)) * Math.sin(dLon / 2) ** 2;
  const km = 2 * 6371.0088 * Math.asin(Math.sqrt(h));
  return Math.ceil((km / AVERAGE_SPEED_KMH) * 60);
}

/** A chat channel bootstrap for an active Booking (Requirement 18.1). */
export interface ChatChannel {
  channelId: string;
  bookingId: string;
  /** False once the Booking reaches PAYMENT_COMPLETED/CANCELLED (Requirement 18.5). */
  active: boolean;
}

/** A single chat message (Requirement 18). Phone numbers are never exposed. */
export interface ChatMessage {
  id: string;
  channelId: string;
  /** 'CUSTOMER' | 'PROVIDER' — the sending party's role, not their identity. */
  senderRole: 'CUSTOMER' | 'PROVIDER';
  body: string;
  /** ISO-8601 timestamp. */
  sentAt: string;
}

/** GET /chat/bookings/{bookingId}/channel — resolve the channel for a Booking. */
export async function fetchChatChannel(bookingId: string): Promise<ChatChannel> {
  const { data } = await apiClient.get<ChatChannel>(`/chat/bookings/${bookingId}/channel`);
  return data;
}

/** GET /chat/channels/{channelId}/messages — message history (Requirement 18.4). */
export async function fetchChatHistory(channelId: string): Promise<ChatMessage[]> {
  const { data } = await apiClient.get<ChatMessage[]>(`/chat/channels/${channelId}/messages`);
  return data;
}
