package com.homefix.provider.catalog;

import java.util.UUID;

/**
 * Abstraction over the Service Catalog Service used to validate that categories and
 * subcategories a provider selects exist and are currently active (Requirements 4.3, 4.8).
 *
 * <p>Cross-service data access is via API calls only (no shared tables), so this port models
 * the remote catalog lookups. It is trivially mockable in unit tests, and a production adapter
 * can wrap the real catalog REST/gRPC client without changing selection business logic.
 */
public interface CatalogClientPort {

    /**
     * @return {@code true} if the category exists and its {@code is_active} flag is set.
     */
    boolean isCategoryActive(UUID categoryId);

    /**
     * @return {@code true} if the subcategory exists, belongs to {@code categoryId}, and both
     *         the subcategory and its parent category are active.
     */
    boolean isSubcategoryActive(UUID categoryId, UUID subcategoryId);
}
