package com.homefix.booking.catalog;

import java.util.UUID;

/**
 * Port abstracting the Service Catalog Service (Task 12). The booking flow checks that the
 * requested category/subcategory is active before creating a booking (Requirement 7.6).
 *
 * <p>Abstracted so it can be backed by an HTTP adapter in production and a mock/stub in tests.
 */
public interface CatalogClientPort {

    /**
     * @return {@code true} iff the subcategory (and its parent category) exists and is active
     *         and thus bookable (Requirement 7.6).
     */
    boolean isSubcategoryActive(UUID categoryId, UUID subcategoryId);
}
