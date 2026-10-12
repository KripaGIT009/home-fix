import { apiClient } from '@api/client';
import type { Tenant, TenantAdmin, TenantMembers, TenantStatus, TeamProvider } from './model';

/**
 * Tenants module bindings for Platform_Admins (Requirement MT-12).
 *
 * Served by provider-service through the gateway's `/admin/tenants/**` route
 * (design.md "provider-service"). Every write is attributed server-side to the
 * acting admin (Requirement MT-1.3).
 *
 * Endpoints:
 * - GET    /admin/tenants[?status=]                     — all Tenants with counts
 * - POST   /admin/tenants                               — create
 * - PUT    /admin/tenants/{id}                          — edit, including status
 * - GET    /admin/tenants/{id}/members                  — admins and providers
 * - POST   /admin/tenants/{id}/admins {mobileNumber}    — add an administrator
 * - DELETE /admin/tenants/{id}/admins/{userId}          — remove an administrator
 * - POST   /admin/tenants/{id}/providers {mobileNumber} — add a provider
 * - DELETE /admin/tenants/{id}/providers/{providerId}   — remove a provider
 * - POST   /admin/tenants/{id}/approval                 — approve an agency application
 * - POST   /admin/tenants/{id}/rejection {reason}       — reject it, with the reason
 */

/** Body of `POST /admin/tenants`. */
export interface TenantPayload {
  name: string;
  contactPhone?: string;
  contactEmail?: string;
  baseLatitude: number;
  baseLongitude: number;
  serviceRadiusKm: number;
  categoryIds: string[];
}

/** Body of `PUT /admin/tenants/{id}`: the create body plus the status. */
export interface TenantUpdatePayload extends TenantPayload {
  status: TenantStatus;
}

/** Every Tenant, or only those in one status (e.g. PENDING_APPROVAL). */
export async function fetchTenants(status?: TenantStatus): Promise<Tenant[]> {
  const { data } = await apiClient.get<Tenant[]>('/admin/tenants', {
    params: status ? { status } : undefined,
  });
  return data;
}

export async function createTenant(payload: TenantPayload): Promise<Tenant> {
  const { data } = await apiClient.post<Tenant>('/admin/tenants', payload);
  return data;
}

export async function updateTenant(id: string, payload: TenantUpdatePayload): Promise<Tenant> {
  const { data } = await apiClient.put<Tenant>(`/admin/tenants/${id}`, payload);
  return data;
}

export async function fetchTenantMembers(id: string): Promise<TenantMembers> {
  const { data } = await apiClient.get<TenantMembers>(`/admin/tenants/${id}/members`);
  return data;
}

export async function addTenantAdmin(id: string, mobileNumber: string): Promise<TenantAdmin> {
  const { data } = await apiClient.post<TenantAdmin>(`/admin/tenants/${id}/admins`, {
    mobileNumber,
  });
  return data;
}

export async function removeTenantAdmin(id: string, userId: string): Promise<void> {
  await apiClient.delete(`/admin/tenants/${id}/admins/${userId}`);
}

export async function addTenantProvider(id: string, mobileNumber: string): Promise<TeamProvider> {
  const { data } = await apiClient.post<TeamProvider>(`/admin/tenants/${id}/providers`, {
    mobileNumber,
  });
  return data;
}

export async function removeTenantProvider(id: string, providerId: string): Promise<void> {
  await apiClient.delete(`/admin/tenants/${id}/providers/${providerId}`);
}

/**
 * Approve an agency application: the Tenant becomes ACTIVE, the applicant its
 * administrator, and the applicant is emailed (email-auth Requirement 5.4).
 */
export async function approveTenantApplication(id: string): Promise<Tenant> {
  const { data } = await apiClient.post<Tenant>(`/admin/tenants/${id}/approval`);
  return data;
}

/** Reject an agency application; the reason (1–500 characters) is emailed to the applicant. */
export async function rejectTenantApplication(id: string, reason: string): Promise<Tenant> {
  const { data } = await apiClient.post<Tenant>(`/admin/tenants/${id}/rejection`, { reason });
  return data;
}
