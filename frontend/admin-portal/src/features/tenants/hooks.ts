import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { UseMutationResult, UseQueryResult } from '@tanstack/react-query';
import type { ApiError } from '@api/client';
import { fetchCategorySummaries, type CategorySummary } from '@features/categories/api';
import {
  addTenantAdmin,
  addTenantProvider,
  approveTenantApplication,
  createTenant,
  fetchTenantMembers,
  fetchTenants,
  rejectTenantApplication,
  removeTenantAdmin,
  removeTenantProvider,
  updateTenant,
  type TenantPayload,
  type TenantUpdatePayload,
} from './api';
import type { Tenant, TenantAdmin, TenantMembers, TenantStatus, TeamProvider } from './model';

export const tenantKeys = {
  all: ['admin', 'tenants'] as const,
  /** Prefix of every list, filtered or not, for invalidation. */
  lists: ['admin', 'tenants', 'list'] as const,
  list: (status: TenantStatus | null) => ['admin', 'tenants', 'list', { status }] as const,
  members: (id: string) => ['admin', 'tenants', 'members', id] as const,
  categories: ['admin', 'catalog', 'category-summaries'] as const,
};

/**
 * Every Tenant with its counts (Requirement MT-12.1), or only those in one
 * status — PENDING_APPROVAL lists the agency applications awaiting a decision
 * (email-auth Requirement 5.6).
 */
export function useTenants(status: TenantStatus | null = null): UseQueryResult<Tenant[], ApiError> {
  return useQuery<Tenant[], ApiError>({
    queryKey: tenantKeys.list(status),
    queryFn: () => fetchTenants(status ?? undefined),
  });
}

/**
 * The catalog's categories, active and inactive. Only active ones may be
 * chosen (Requirement MT-1.2), but inactive ones are still needed to name a
 * category an existing Tenant was given before it was deactivated.
 */
export function useCatalogCategories(): UseQueryResult<CategorySummary[], ApiError> {
  return useQuery<CategorySummary[], ApiError>({
    queryKey: tenantKeys.categories,
    queryFn: fetchCategorySummaries,
    staleTime: 5 * 60_000,
  });
}

/** Create a Tenant, or edit one when `id` is given (Requirement MT-12.2). */
export function useSaveTenant(): UseMutationResult<
  Tenant,
  ApiError,
  { id: string; payload: TenantUpdatePayload } | { id?: undefined; payload: TenantPayload }
> {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, payload }) => (id ? updateTenant(id, payload) : createTenant(payload)),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: tenantKeys.lists });
    },
  });
}

/** A Tenant's administrators and providers. */
export function useTenantMembers(id: string): UseQueryResult<TenantMembers, ApiError> {
  return useQuery<TenantMembers, ApiError>({
    queryKey: tenantKeys.members(id),
    queryFn: () => fetchTenantMembers(id),
  });
}

/**
 * Membership writes refresh the members list and the Tenant list, whose
 * provider and admin counts they change.
 */
function useMembershipMutation<Variables, Result>(
  mutationFn: (variables: Variables) => Promise<Result>,
): UseMutationResult<Result, ApiError, Variables> {
  const queryClient = useQueryClient();
  return useMutation<Result, ApiError, Variables>({
    mutationFn,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: tenantKeys.all });
    },
  });
}

export function useAddTenantAdmin(tenantId: string) {
  return useMembershipMutation<string, TenantAdmin>((mobile) => addTenantAdmin(tenantId, mobile));
}

export function useRemoveTenantAdmin(tenantId: string) {
  return useMembershipMutation<string, void>((userId) => removeTenantAdmin(tenantId, userId));
}

export function useAddTenantProvider(tenantId: string) {
  return useMembershipMutation<string, TeamProvider>((mobile) =>
    addTenantProvider(tenantId, mobile),
  );
}

export function useRemoveTenantProvider(tenantId: string) {
  return useMembershipMutation<string, void>((providerId) =>
    removeTenantProvider(tenantId, providerId),
  );
}

/** A decision on an agency application: approve, or reject with a reason. */
export type ApplicationDecision = { approve: true } | { approve: false; reason: string };

/**
 * Approve or reject an agency application (email-auth Requirements 5.4, 5.6).
 * Refreshes every list: the Tenant leaves the pending list and changes status
 * in the full one.
 */
export function useDecideApplication(
  tenantId: string,
): UseMutationResult<Tenant, ApiError, ApplicationDecision> {
  const queryClient = useQueryClient();
  return useMutation<Tenant, ApiError, ApplicationDecision>({
    mutationFn: (decision) =>
      decision.approve
        ? approveTenantApplication(tenantId)
        : rejectTenantApplication(tenantId, decision.reason),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: tenantKeys.lists });
    },
  });
}
