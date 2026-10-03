import { apiClient } from '@api/client';

/**
 * Provider Management bindings (Requirement 19.2).
 *
 * Endpoints (see design.md — Admin Service / Provider Service):
 * - GET   /admin/providers             — list/search providers
 * - PATCH /admin/providers/{id}/status — activate/deactivate a provider
 * - POST  /admin/providers/{id}/bank-account/verification — mark the bank account verified
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
  /** The settlement bank account; null when the provider has not added one. */
  bankAccount?: ProviderBankAccount | null;
}

/** A provider's settlement bank account, as the admin list shows it. */
export interface ProviderBankAccount {
  /** e.g. "HDFC ••••1234"; never the full number. */
  masked: string;
  verified: boolean;
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

/**
 * POST /admin/providers/{id}/bank-account/verification — record that the
 * provider's bank account has been checked, so settlements can be paid into it.
 * 404 BANK_ACCOUNT_NOT_FOUND when the provider has no account on file.
 */
export async function verifyProviderBankAccount(id: string): Promise<ProviderBankAccount> {
  const { data } = await apiClient.post<ProviderBankAccount>(
    `/admin/providers/${id}/bank-account/verification`,
  );
  return data;
}
