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

export interface AdminProvider {
  id: string;
  displayName: string;
  mobileNumber: string;
  primarySkill: string;
  verificationStatus: VerificationStatus;
  rating: number;
  completedJobs: number;
  isOnline: boolean;
  status: 'ACTIVE' | 'SUSPENDED' | 'DEACTIVATED';
}

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
  status: AdminProvider['status'],
): Promise<AdminProvider> {
  const { data } = await apiClient.patch<AdminProvider>(`/admin/providers/${id}/status`, {
    status,
  });
  return data;
}
