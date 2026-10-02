package com.homefix.pricing.config;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.homefix.pricing.domain.PricingParameters;

/**
 * Source of truth for Admin-configured pricing parameters (Requirement 6.11).
 *
 * <p>Backed by {@code pricing.pricing_parameters} ({@code JpaPricingParametersAdapter}), written
 * through the Admin API; modelled as a port so it is mockable in unit tests and decoupled from
 * the cache. The
 * {@code PricingConfigService} reads through the {@code PricingConfigCachePort} and falls back
 * to this port on a cache miss.
 */
public interface PricingParametersPort {

    /** @return the configured parameters for a subcategory, or empty if none are configured. */
    Optional<PricingParameters> findBySubcategoryId(UUID subcategoryId);

    /**
     * Every configured parameter set, most recently updated first, capped at {@code limit} rows —
     * the Admin Portal's pricing table (Requirement 6.11). Reads the source of truth, not the
     * cache: the cache is keyed by subcategory and cannot enumerate.
     */
    List<PricingParameters> findAll(int limit);

    /** Persists Admin-updated parameters and returns the stored value. */
    PricingParameters save(PricingParameters parameters);
}
