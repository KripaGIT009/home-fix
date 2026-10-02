import { apiClient } from '@api/client';

/**
 * Provider Management bindings (Requirement 19.2).
 *
 * Endpoints (see design.md — Admin Service / Provider Service):
 * - GET   /admin/providers             — list/search providers
 * - PATCH /admin/providers/{id}/status — activate/deactivate a provider
 */

export type VerificationStatus =
  'PENDING' | 'DOCUMENT_SUBMITTED' | 'APPROVED' | 'REJECTED' | 'SUSPENDED';

/**
 * A provider row. The Provider Service owns the profile; fields held by other
 * services (the login mobile number, verification state, job counts, live
 * availability) come back null or absent rather than being fetched across
 * services, and the screen renders a dash for each.
 */
export interface AdminProvider {
  id: string;
  displayName: string;
  mobileNumber?: string | null;
  primarySkill?: string | null;
  /** Null when the Verification Service could not be reached. */
  verificationStatus?: VerificationStatus | null;
  rating?: number | null;
  completedJobs?: number | null;
  isOnline?: boolean | null;
  /** Null when the Verification Service (which holds suspension) is unreachable. */
  status?: ProviderStatus | null;
}

export type ProviderStatus = 'ACTIVE' | 'SUSPENDED' | 'DEACTIVATED';

/**
 * The statuses an admin may set. Deactivation is not supported for providers
 * (the service answers 400 UNSUPPORTED_PROVIDER_STATUS), so it is never offered.
 */
export type ProviderStatusChange = 'ACTIVE' | 'SUSPENDED';

/** GET /admin/providers — providers, optionally filtered by a search term. */
export async function fetchProviders(search?: string): Promise<AdminProvider[]> {
  const { data } = await apiClient.get<AdminProvider[]>('/admin/providers', {
    params: search ? { search } : undefined,
  });
  return data;
}

/** PATCH /admin/providers/{id}/status — change a provider's account status. */
export async function updateProviderStatus(
  id: string,
  status: ProviderStatusChange,
): Promise<AdminProvider> {
  const { data } = await apiClient.patch<AdminProvider>(`/admin/providers/${id}/status`, {
    status,
  });
  return data;
}
