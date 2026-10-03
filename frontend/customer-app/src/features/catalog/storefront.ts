import type { ServiceCategory, ServiceSubcategory } from './api';

/**
 * Pure helpers that turn the catalog into the storefront's rails and banners.
 *
 * Everything the home screen shows is derived here from the one cached
 * GET /catalog/categories response: prices, durations, 24×7 flags and counts.
 * The catalog carries no ratings, booking volumes or discounts, so nothing in
 * the storefront may suggest them.
 */

/** A bookable service together with the category it belongs to. */
export interface ServiceMatch {
  category: ServiceCategory;
  subcategory: ServiceSubcategory;
}

/** Visits at or under this many minutes count as a "quick fix". */
export const QUICK_FIX_MAX_MINUTES = 60;

/** Every service in catalog order, each paired with its category. */
export function flattenServices(categories: ServiceCategory[] | undefined): ServiceMatch[] {
  return (categories ?? []).flatMap((category) =>
    (category.subcategories ?? []).map((subcategory) => ({ category, subcategory })),
  );
}

/** Case-insensitive substring match over a service's name, description and category. */
export function matchesQuery(query: string, { category, subcategory }: ServiceMatch): boolean {
  const needle = query.trim().toLowerCase();
  if (!needle) return false;
  return [subcategory.name, subcategory.description, category.name].some((field) =>
    field?.toLowerCase().includes(needle),
  );
}

/** Services with a short estimated visit, quickest first. */
export function quickFixes(services: ServiceMatch[]): ServiceMatch[] {
  return services
    .filter((match) => match.subcategory.estimatedDurationMin <= QUICK_FIX_MAX_MINUTES)
    .sort((a, b) => a.subcategory.estimatedDurationMin - b.subcategory.estimatedDurationMin);
}

/** The lowest-priced service in each category that has any, in catalog order. */
export function cheapestPerCategory(categories: ServiceCategory[] | undefined): ServiceMatch[] {
  return (categories ?? []).flatMap((category) => {
    const [cheapest] = [...(category.subcategories ?? [])].sort(
      (a, b) => a.basePrice - b.basePrice,
    );
    return cheapest ? [{ category, subcategory: cheapest }] : [];
  });
}

/**
 * Up to `count` services for the hero mosaic: one per category first so the
 * tiles show the breadth of the catalog, then any remaining services.
 */
export function featuredServices(
  categories: ServiceCategory[] | undefined,
  count: number,
): ServiceMatch[] {
  const picks = cheapestPerCategory(categories);
  const chosen = new Set(picks.map((match) => match.subcategory.id));
  const rest = flattenServices(categories).filter((match) => !chosen.has(match.subcategory.id));
  return [...picks, ...rest].slice(0, count);
}

/** "1 service" / "3 services". */
export function pluralise(count: number, singular: string, plural = `${singular}s`): string {
  return `${count} ${count === 1 ? singular : plural}`;
}
