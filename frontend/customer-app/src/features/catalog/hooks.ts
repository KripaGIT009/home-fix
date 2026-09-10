import { useMemo } from 'react';
import { useQuery } from '@tanstack/react-query';
import type { UseQueryResult } from '@tanstack/react-query';
import type { ApiError } from '@api/client';
import { fetchCategories, type ServiceCategory, type ServiceSubcategory } from './api';

/**
 * TanStack Query hooks for the Service Catalog (Requirement 3).
 *
 * The Catalog Service exposes a single customer-facing endpoint,
 * GET /catalog/categories, which returns active categories with their active
 * subcategories nested. All per-id lookups below are therefore derived from
 * that one cached query rather than calling separate endpoints (which do not
 * exist on the backend). Catalog data changes rarely and the service may serve
 * data up to 300 s stale (Requirement 3.8), so a 5-minute client cache applies.
 */

/** 5 minutes, per Task 32 catalog cache requirement. */
export const CATALOG_STALE_TIME_MS = 5 * 60_000;
const CATALOG_GC_TIME_MS = 10 * 60_000;

/** Query keys for catalog data — exported for cache invalidation/prefetch. */
export const catalogKeys = {
  categories: ['catalog', 'categories'] as const,
  category: (categoryId: string) => ['catalog', 'category', categoryId] as const,
  subcategories: (categoryId: string) => ['catalog', 'subcategories', categoryId] as const,
  subcategory: (subcategoryId: string) => ['catalog', 'subcategory', subcategoryId] as const,
};

/** List active service categories for the Home screen. */
export function useCategories(): UseQueryResult<ServiceCategory[], ApiError> {
  return useQuery<ServiceCategory[], ApiError>({
    queryKey: catalogKeys.categories,
    queryFn: fetchCategories,
    staleTime: CATALOG_STALE_TIME_MS,
    gcTime: CATALOG_GC_TIME_MS,
  });
}

/**
 * A single category (for the subcategory-screen breadcrumb), derived from the
 * cached categories list.
 */
export function useCategory(categoryId: string): {
  data: ServiceCategory | undefined;
  isLoading: boolean;
  isError: boolean;
  error: ApiError | null;
} {
  const { data, isLoading, isError, error } = useCategories();
  const category = useMemo(() => data?.find((c) => c.id === categoryId), [data, categoryId]);
  return { data: category, isLoading, isError, error };
}

/**
 * Active subcategories under a category, derived from the cached categories
 * list (the backend returns them nested under each category).
 */
export function useSubcategories(categoryId: string): {
  data: ServiceSubcategory[] | undefined;
  isLoading: boolean;
  isError: boolean;
  error: ApiError | null;
  refetch: () => void;
} {
  const { data, isLoading, isError, error, refetch } = useCategories();
  const subcategories = useMemo(
    () => data?.find((c) => c.id === categoryId)?.subcategories ?? [],
    [data, categoryId],
  );
  return {
    data: data ? subcategories : undefined,
    isLoading,
    isError,
    error,
    refetch: () => void refetch(),
  };
}

/** A single subcategory (for the service-request/estimate screens). */
export function useSubcategory(subcategoryId: string): {
  data: ServiceSubcategory | undefined;
  isLoading: boolean;
  isError: boolean;
  error: ApiError | null;
  refetch: () => void;
} {
  const { data, isLoading, isError, error, refetch } = useCategories();
  const subcategory = useMemo(
    () => data?.flatMap((c) => c.subcategories ?? []).find((s) => s.id === subcategoryId),
    [data, subcategoryId],
  );
  return { data: subcategory, isLoading, isError, error, refetch: () => void refetch() };
}
