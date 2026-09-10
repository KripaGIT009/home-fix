package com.homefix.pricing.config;

import java.util.Optional;
import java.util.UUID;

import com.homefix.pricing.domain.PricingParameters;

/**
 * Source of truth for Admin-configured pricing parameters (Requirement 6.11).
 *
 * <p>In production this is backed by the pricing-parameters table written by the Admin portal;
 * modelled as a port so it is mockable in unit tests and decoupled from the cache. The
 * {@code PricingConfigService} reads through the {@code PricingConfigCachePort} and falls back
 * to this port on a cache miss.
 */
public interface PricingParametersPort {

    /** @return the configured parameters for a subcategory, or empty if none are configured. */
    Optional<PricingParameters> findBySubcategoryId(UUID subcategoryId);

    /** Persists Admin-updated parameters and returns the stored value. */
    PricingParameters save(PricingParameters parameters);
}
