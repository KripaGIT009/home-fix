import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { UseMutationResult, UseQueryResult } from '@tanstack/react-query';
import type { ApiError } from '@api/client';
import { fetchCategorySummaries, type CategorySummary } from '@features/categories/api';
import {
  addTenantAdmin,
  addTenantProvider,
  createTenant,
  fetchTenantMembers,
  fetchTenants,
  removeTenantAdmin,
  removeTenantProvider,
  updateTenant,
  type TenantPayload,
  type TenantUpdatePayload,
} from './api';
import type { Tenant, TenantAdmin, TenantMembers, TeamProvider } from './model';

export const tenantKeys = {
  all: ['admin', 'tenants'] as const,
  list: ['admin', 'tenants', 'list'] as const,
  members: (id: string) => ['admin', 'tenants', 'members', id] as const,
  categories: ['admin', 'catalog', 'category-summaries'] as const,
};

/** Every Tenant with its counts (Requirement MT-12.1). */
export function useTenants(): UseQueryResult<Tenant[], ApiError> {
  return useQuery<Tenant[], ApiError>({ queryKey: tenantKeys.list, queryFn: fetchTenants });
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
      void queryClient.invalidateQueries({ queryKey: tenantKeys.list });
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
