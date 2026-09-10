import { apiClient } from '@api/client';

/**
 * Service Catalog Service API bindings (Requirement 3).
 *
 * Endpoints (see design.md — Service Catalog Service, routed via API Gateway):
 * - GET /catalog/categories                    — active categories
 * - GET /catalog/categories/{categoryId}/subcategories — active subcategories
 *
 * The Catalog Service only returns active categories/subcategories to
 * customer-facing APIs (Requirement 3.5) and its responses reflect DB state as
 * of no more than 300 s ago, so cache data may be served (Requirement 3.8) —
 * hence the 5-minute client-side cache used by the query hooks.
 */

/** A service category card shown on the Home screen (Requirement 3.2). */
export interface ServiceCategory {
  id: string;
  name: string;
  description?: string;
  /** Icon key/name (server-defined) used to pick a display icon. */
  icon?: string;
  /** Icon reference as the Catalog Service names it; may be null. */
  iconUrl?: string | null;
  displayOrder: number;
  /** Active subcategories, returned nested by GET /catalog/categories. */
  subcategories?: ServiceSubcategory[];
}

/** A subcategory under a category, carrying pricing attributes (Requirement 3.7). */
export interface ServiceSubcategory {
  id: string;
  categoryId: string;
  name: string;
  description?: string;
  /** Base price in the platform currency (0.01–999,999.99). */
  basePrice: number;
  /** Estimated duration in minutes (1–480). */
  estimatedDurationMin: number;
  /** Whether this subcategory can be booked as an emergency (Requirement 3.7). */
  emergencyAvailable: boolean;
}

/** GET /catalog/categories — list active service categories. */
export async function fetchCategories(): Promise<ServiceCategory[]> {
  const { data } = await apiClient.get<ServiceCategory[]>('/catalog/categories');
  // Present categories in the Admin-defined display order (Requirement 3.2).
  return [...data].sort((a, b) => a.displayOrder - b.displayOrder);
}

/** GET /catalog/categories/{categoryId} — a single active category. */
export async function fetchCategory(categoryId: string): Promise<ServiceCategory> {
  const { data } = await apiClient.get<ServiceCategory>(`/catalog/categories/${categoryId}`);
  return data;
}

/** GET /catalog/categories/{categoryId}/subcategories — list active subcategories. */
export async function fetchSubcategories(categoryId: string): Promise<ServiceSubcategory[]> {
  const { data } = await apiClient.get<ServiceSubcategory[]>(
    `/catalog/categories/${categoryId}/subcategories`,
  );
  return data;
}

/** GET /catalog/subcategories/{subcategoryId} — a single active subcategory. */
export async function fetchSubcategory(subcategoryId: string): Promise<ServiceSubcategory> {
  const { data } = await apiClient.get<ServiceSubcategory>(
    `/catalog/subcategories/${subcategoryId}`,
  );
  return data;
}
