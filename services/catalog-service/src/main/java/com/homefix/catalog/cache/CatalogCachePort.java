package com.homefix.catalog.cache;

import java.util.List;
import java.util.Optional;

import com.homefix.catalog.api.dto.CategoryView;

/**
 * Abstraction over the read-through cache backing customer-facing catalog listings
 * (Requirement 3.8). Modelled as a port so the caching technology (Redis in production) can
 * vary without touching the catalog business logic, and so it is trivially mockable in unit
 * tests.
 *
 * <p>The cache holds the fully-resolved, active-only customer view. On any Admin write the
 * service invalidates the cache so subsequent reads reflect the new database state within the
 * cache TTL (default 300 s).
 */
public interface CatalogCachePort {

    /** Cache key for the customer-facing active catalog listing. */
    String ACTIVE_CATALOG_KEY = "catalog:active";

    /**
     * @return the cached active catalog listing if present and not expired, otherwise empty.
     */
    Optional<List<CategoryView>> getActiveCatalog();

    /**
     * Stores the active catalog listing with the configured TTL. Serving from this entry keeps
     * responses within the freshness bound of Requirement 3.8.
     */
    void putActiveCatalog(List<CategoryView> catalog);

    /**
     * Invalidates the cached catalog. Called on every Admin create/update/delete/activation
     * change so the next read is served fresh from the database (Requirement 3.8).
     */
    void invalidate();
}
