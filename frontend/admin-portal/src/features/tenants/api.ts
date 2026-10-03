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
 * - GET    /admin/tenants                               — all Tenants with counts
 * - POST   /admin/tenants                               — create
 * - PUT    /admin/tenants/{id}                          — edit, including status
 * - GET    /admin/tenants/{id}/members                  — admins and providers
 * - POST   /admin/tenants/{id}/admins {mobileNumber}    — add an administrator
 * - DELETE /admin/tenants/{id}/admins/{userId}          — remove an administrator
 * - POST   /admin/tenants/{id}/providers {mobileNumber} — add a provider
 * - DELETE /admin/tenants/{id}/providers/{providerId}   — remove a provider
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

export async function fetchTenants(): Promise<Tenant[]> {
  const { data } = await apiClient.get<Tenant[]>('/admin/tenants');
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
