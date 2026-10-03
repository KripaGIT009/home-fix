import { apiClient } from '@api/client';

/**
 * Service Category Management bindings (Requirement 19.2).
 *
 * Admins maintain the service catalog — categories and their subcategories —
 * and can activate or deactivate entries. Bookings for a deactivated category
 * or subcategory are rejected by the Booking Service (Requirement 7.6).
 *
 * These screens are served by the Service Catalog Service's admin surface, which
 * differs from the shape this module exposes in two ways (see API_CONTRACTS.md):
 *
 *  - it reports an `active` boolean rather than a `status` string, and
 *  - it lists subcategories per category rather than nesting them in the
 *    category payload.
 *
 * The mapping is kept here so the screens keep working against one stable shape.
 *
 * Endpoints:
 * - GET  /admin/catalog/categories                          — categories, including inactive
 * - GET  /admin/catalog/categories/{id}/subcategories       — subcategories, including inactive
 * - POST /admin/catalog/categories/{id}/activate|deactivate — flip a category
 * - POST /admin/catalog/subcategories/{id}/activate|deactivate — flip a subcategory
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

/** The catalog service's own category shape. */
interface CatalogCategoryResponse {
  id: string;
  name: string;
  active: boolean;
}

/** The catalog service's own subcategory shape; only these fields are used here. */
interface CatalogSubcategoryResponse {
  id: string;
  name: string;
  active: boolean;
}

/** The catalog service speaks `active`; these screens speak `status`. */
function toStatus(active: boolean): CatalogStatus {
  return active ? 'ACTIVE' : 'INACTIVE';
}

/** Activate and deactivate are separate endpoints rather than a status field. */
function statusPath(status: CatalogStatus): 'activate' | 'deactivate' {
  return status === 'ACTIVE' ? 'activate' : 'deactivate';
}

/**
 * The full service catalog with subcategories.
 *
 * Subcategories are fetched per category and in parallel: the catalog is small
 * (a handful of categories) and the list endpoint does not nest them.
 */
export async function fetchCategories(): Promise<ServiceCategory[]> {
  const { data: categories } = await apiClient.get<CatalogCategoryResponse[]>(
    '/admin/catalog/categories',
  );
  return Promise.all(
    categories.map(async (category) => {
      const { data: subcategories } = await apiClient.get<CatalogSubcategoryResponse[]>(
        `/admin/catalog/categories/${category.id}/subcategories`,
      );
      return {
        id: category.id,
        name: category.name,
        status: toStatus(category.active),
        subcategories: subcategories.map((sub) => ({
          id: sub.id,
          name: sub.name,
          status: toStatus(sub.active),
        })),
      };
    }),
  );
}

/** A category without its subcategories, for pickers that only need the name. */
export interface CategorySummary {
  id: string;
  name: string;
  status: CatalogStatus;
}

/**
 * Categories only, active and inactive, in one call. The Tenants module picks
 * a Tenant's categories from these (Requirement MT-12.2) and has no use for the
 * per-category subcategory fetches fetchCategories makes.
 */
export async function fetchCategorySummaries(): Promise<CategorySummary[]> {
  const { data } = await apiClient.get<CatalogCategoryResponse[]>('/admin/catalog/categories');
  return data.map((category) => ({
    id: category.id,
    name: category.name,
    status: toStatus(category.active),
  }));
}

/** Activate or deactivate a category. */
export async function updateCategoryStatus(
  id: string,
  status: CatalogStatus,
): Promise<ServiceCategory> {
  const { data } = await apiClient.post<CatalogCategoryResponse>(
    `/admin/catalog/categories/${id}/${statusPath(status)}`,
  );
  const { data: subcategories } = await apiClient.get<CatalogSubcategoryResponse[]>(
    `/admin/catalog/categories/${id}/subcategories`,
  );
  return {
    id: data.id,
    name: data.name,
    status: toStatus(data.active),
    subcategories: subcategories.map((sub) => ({
      id: sub.id,
      name: sub.name,
      status: toStatus(sub.active),
    })),
  };
}

/** Activate or deactivate a subcategory. */
export async function updateSubcategoryStatus(
  id: string,
  status: CatalogStatus,
): Promise<ServiceSubcategory> {
  const { data } = await apiClient.post<CatalogSubcategoryResponse>(
    `/admin/catalog/subcategories/${id}/${statusPath(status)}`,
  );
  return { id: data.id, name: data.name, status: toStatus(data.active) };
}
