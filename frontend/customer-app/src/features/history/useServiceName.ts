import { useCategory, useSubcategory } from '@features/catalog/hooks';
import type { ServiceCategory } from '@features/catalog/api';

/** The Booking Service's placeholder when it cannot resolve a service name. */
const PLACEHOLDER_NAME = 'Service';

/**
 * Resolves a booking's display name and category from the cached catalog.
 *
 * The Booking Service falls back to the literal "Service" when it cannot
 * resolve the name; the catalog the app already caches usually can, by the
 * booking's subcategory id. The category (for its icon and colour) comes from
 * the same cache.
 */
export function useBookingService(
  serviceName: string | undefined,
  subcategoryId: string | undefined,
): { name: string | undefined; category: ServiceCategory | undefined } {
  const subcategory = useSubcategory(subcategoryId ?? '').data;
  const category = useCategory(subcategory?.categoryId ?? '').data;
  const name =
    serviceName && serviceName !== PLACEHOLDER_NAME
      ? serviceName
      : (subcategory?.name ?? serviceName);
  return { name, category };
}
