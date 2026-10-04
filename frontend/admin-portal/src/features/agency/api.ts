import { apiClient } from '@api/client';
import type { TenantPayload } from '@features/tenants/api';
import type { TenantStatus } from '@features/tenants/model';

/**
 * Agency self-registration bindings (email-auth Requirement 5), for any
 * signed-in account. Served by provider-service through the gateway's
 * `/tenant-applications/**` route; the applicant is always the caller, never a
 * request field.
 *
 * Endpoints:
 * - POST /tenant-applications     — apply with the Tenant body; 409 APPLICATION_EXISTS
 * - GET  /tenant-applications/me  — the caller's latest application; 404 APPLICATION_NOT_FOUND
 */

/** An agency's own application, as its applicant reads it. */
export interface TenantApplication {
  tenantId: string;
  name: string;
  /** PENDING_APPROVAL, ACTIVE once approved, REJECTED; SUSPENDED later on. */
  status: TenantStatus;
  rejectionReason?: string | null;
  createdAt: string;
}

/** errorCode of GET /tenant-applications/me for a caller who never applied. */
export const APPLICATION_NOT_FOUND_CODE = 'APPLICATION_NOT_FOUND';

/** POST /tenant-applications — the application, in PENDING_APPROVAL. */
export async function applyForAgency(payload: TenantPayload): Promise<TenantApplication> {
  const { data } = await apiClient.post<TenantApplication>('/tenant-applications', payload);
  return data;
}

/** GET /tenant-applications/me — the caller's latest application. */
export async function fetchMyApplication(): Promise<TenantApplication> {
  const { data } = await apiClient.get<TenantApplication>('/tenant-applications/me');
  return data;
}
