package com.homefix.booking.catalog;

import java.util.Map;
import java.util.UUID;

/**
 * Port abstracting the Service Catalog Service (Task 12). The booking flow checks that the
 * requested category/subcategory is active before creating a booking (Requirement 7.6), and the
 * booking read endpoints label each booking with its subcategory's display name.
 *
 * <p>Abstracted so it can be backed by an HTTP adapter in production and a mock/stub in tests.
 */
public interface CatalogClientPort {

    /**
     * @return {@code true} iff the subcategory (and its parent category) exists and is active
     *         and thus bookable (Requirement 7.6).
     */
    boolean isSubcategoryActive(UUID categoryId, UUID subcategoryId);

    /**
     * Display names for every active subcategory, keyed by id.
     *
     * <p>Returned as a whole map rather than one lookup per id because the catalog's listing is
     * a single cache-backed call: resolving a page of history costs one request here, where a
     * per-booking lookup would cost one per row.
     *
     * <p>Callers must tolerate a missing id. The map is empty when the catalog cannot be
     * reached, and a subcategory deactivated after a booking was placed is absent from the
     * listing while the booking that references it still exists.
     *
     * <p>Defaults to the empty map — the "names unknown" answer — which is what the stub adapter
     * gives: it has no catalog to read names from, and a label is never worth inventing.
     */
    default Map<UUID, String> subcategoryNames() {
        return Map.of();
    }
}
