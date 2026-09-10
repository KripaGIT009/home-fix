import { apiClient } from '@api/client';

/**
 * Service Category Management bindings (Requirement 19.2).
 *
 * Admins maintain the service catalog — categories and their subcategories —
 * and can activate or deactivate entries. Bookings for a deactivated category
 * or subcategory are rejected by the Booking Service (Requirement 7.6).
 *
 * Endpoints (see design.md — Service Catalog Service / Admin Service):
 * - GET   /admin/categories                    — categories with subcategories
 * - PATCH /admin/categories/{id}/status        — activate/deactivate a category
 * - PATCH /admin/subcategories/{id}/status     — activate/deactivate a subcategory
 */

export type CatalogStatus = 'ACTIVE' | 'INACTIVE';

export interface ServiceSubcategory {
  id: string;
  name: string;
  status: CatalogStatus;
}

export interface ServiceCategory {
  id: string;
  name: string;
  status: CatalogStatus;
  subcategories: ServiceSubcategory[];
}

/** GET /admin/categories — the full service catalog with subcategories. */
export async function fetchCategories(): Promise<ServiceCategory[]> {
  const { data } = await apiClient.get<ServiceCategory[]>('/admin/categories');
  return data;
}

/** PATCH /admin/categories/{id}/status — activate or deactivate a category. */
export async function updateCategoryStatus(
  id: string,
  status: CatalogStatus,
): Promise<ServiceCategory> {
  const { data } = await apiClient.patch<ServiceCategory>(`/admin/categories/${id}/status`, {
    status,
  });
  return data;
}

/** PATCH /admin/subcategories/{id}/status — activate or deactivate a subcategory. */
export async function updateSubcategoryStatus(
  id: string,
  status: CatalogStatus,
): Promise<ServiceSubcategory> {
  const { data } = await apiClient.patch<ServiceSubcategory>(`/admin/subcategories/${id}/status`, {
    status,
  });
  return data;
}
