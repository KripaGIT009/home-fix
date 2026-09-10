package com.homefix.pricing.cache;

import java.util.Optional;
import java.util.UUID;

import com.homefix.pricing.domain.PricingParameters;

/**
 * Abstraction over the Admin pricing-parameter cache (Requirement 6.11).
 *
 * <p>Modelled as a port so the caching technology (Redis in production) can vary without
 * touching the pricing business logic and so it is trivially mockable in unit tests. Cached
 * entries expire after the configured TTL (default 60 s); any Admin update invalidates the
 * entry so the next read repopulates from the source of truth, guaranteeing updated parameters
 * reach new bookings within 60 s.
 */
public interface PricingConfigCachePort {

    /** @return the cached parameters for the subcategory if present and not expired. */
    Optional<PricingParameters> get(UUID subcategoryId);

    /** Stores parameters for the subcategory with the configured TTL. */
    void put(PricingParameters parameters);

    /** Invalidates the cached parameters for a subcategory (called on Admin update). */
    void invalidate(UUID subcategoryId);
}
