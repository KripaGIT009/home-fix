import { apiClient } from '@api/client';
import type { BookingStatus } from '@features/bookings/api';
import type { Tenant, TeamProvider } from '@features/tenants/model';

/**
 * Tenant Portal bindings for a TENANT_ADMIN (Requirement MT-11).
 *
 * The caller's Tenant is never sent: both services resolve it from the
 * caller's identity (Requirement MT-10.1), so there is no tenant id in any
 * path below and no way for the portal to widen its own scope.
 *
 * provider-service (gateway `/tenant/me`, `/tenant/providers/**`):
 * - GET    /tenant/me                         — the caller's Tenant
 * - GET    /tenant/providers                  — the team, with availability and assignability
 * - POST   /tenant/providers {mobileNumber}   — add a provider
 * - DELETE /tenant/providers/{providerId}     — remove a provider
 *
 * booking-service (gateway `/tenant/bookings/**`):
 * - GET  /tenant/bookings/queue                       — Assignment_Queue, oldest first
 * - GET  /tenant/bookings?status=                     — the Tenant's bookings, newest first, ≤ 200
 * - POST /tenant/bookings/{key}/assignment {providerId} — assign a queued booking
 */

/** A point on the map, as booking-service serialises it. */
export interface Coordinates {
  latitude: number;
  longitude: number;
}

/**
 * The booking-service `TenantBooking` payload. `address` and `coordinates`
 * come from the customer's saved address and are null when it cannot be
 * resolved right now; `queuedAt` is when the booking first entered the queue
 * and is kept across a Provider's decline (Requirement MT-7.2).
 */
export interface TenantBooking {
  id: string;
  reference: string;
  serviceName?: string | null;
  status: BookingStatus;
  isEmergency: boolean;
  scheduledAt?: string | null;
  createdAt: string;
  queuedAt?: string | null;
  amount: number;
  currency: string;
  address?: string | null;
  coordinates?: Coordinates | null;
  providerId?: string | null;
}

/** The most rows `GET /tenant/bookings` returns (Requirement MT-8.3). */
export const TENANT_BOOKINGS_LIMIT = 200;

export async function fetchMyTenant(): Promise<Tenant> {
  const { data } = await apiClient.get<Tenant>('/tenant/me');
  return data;
}

export async function fetchTeam(): Promise<TeamProvider[]> {
  const { data } = await apiClient.get<TeamProvider[]>('/tenant/providers');
  return data;
}

export async function addTeamProvider(mobileNumber: string): Promise<TeamProvider> {
  const { data } = await apiClient.post<TeamProvider>('/tenant/providers', { mobileNumber });
  return data;
}

export async function removeTeamProvider(providerId: string): Promise<void> {
  await apiClient.delete(`/tenant/providers/${providerId}`);
}

export async function fetchQueue(): Promise<TenantBooking[]> {
  const { data } = await apiClient.get<TenantBooking[]>('/tenant/bookings/queue');
  return data;
}

export async function fetchTenantBookings(status: BookingStatus | ''): Promise<TenantBooking[]> {
  const { data } = await apiClient.get<TenantBooking[]>('/tenant/bookings', {
    params: status ? { status } : undefined,
  });
  return data;
}

export async function assignBooking(key: string, providerId: string): Promise<TenantBooking> {
  const { data } = await apiClient.post<TenantBooking>(`/tenant/bookings/${key}/assignment`, {
    providerId,
  });
  return data;
}
