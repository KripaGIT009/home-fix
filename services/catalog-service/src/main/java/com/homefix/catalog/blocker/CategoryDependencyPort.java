package com.homefix.catalog.blocker;

import java.util.UUID;

/**
 * Abstraction over the cross-service lookups that decide whether a category can be deleted
 * (Requirement 3.6). Per the design's per-service database isolation rule, the Catalog Service
 * never reads Provider or Booking tables directly — it asks those services via API calls. This
 * port hides that transport so the deletion logic is testable with deterministic mocks.
 */
public interface CategoryDependencyPort {

    /**
     * Returns the active providers and active bookings referencing subcategories of the given
     * category, with their counts and identifiers (Requirement 3.6). Returns
     * {@link CategoryBlockers#none()} when the category has no blockers.
     */
    CategoryBlockers findBlockers(UUID categoryId);
}
