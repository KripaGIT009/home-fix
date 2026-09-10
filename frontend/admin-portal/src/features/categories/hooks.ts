import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { UseMutationResult, UseQueryResult } from '@tanstack/react-query';
import type { ApiError } from '@api/client';
import {
  fetchCategories,
  updateCategoryStatus,
  updateSubcategoryStatus,
  type CatalogStatus,
  type ServiceCategory,
  type ServiceSubcategory,
} from './api';

export const categoryKeys = {
  list: ['admin', 'categories'] as const,
};

/** The full service catalog with subcategories (Requirement 19.2). */
export function useCategories(): UseQueryResult<ServiceCategory[], ApiError> {
  return useQuery<ServiceCategory[], ApiError>({
    queryKey: categoryKeys.list,
    queryFn: fetchCategories,
  });
}

/** Activate or deactivate a service category. */
export function useUpdateCategoryStatus(): UseMutationResult<
  ServiceCategory,
  ApiError,
  { id: string; status: CatalogStatus }
> {
  const queryClient = useQueryClient();
  return useMutation<ServiceCategory, ApiError, { id: string; status: CatalogStatus }>({
    mutationFn: ({ id, status }) => updateCategoryStatus(id, status),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: categoryKeys.list });
    },
  });
}

/** Activate or deactivate a service subcategory. */
export function useUpdateSubcategoryStatus(): UseMutationResult<
  ServiceSubcategory,
  ApiError,
  { id: string; status: CatalogStatus }
> {
  const queryClient = useQueryClient();
  return useMutation<ServiceSubcategory, ApiError, { id: string; status: CatalogStatus }>({
    mutationFn: ({ id, status }) => updateSubcategoryStatus(id, status),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: categoryKeys.list });
    },
  });
}
