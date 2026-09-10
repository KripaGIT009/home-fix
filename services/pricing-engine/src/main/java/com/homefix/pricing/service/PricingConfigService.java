package com.homefix.pricing.service;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.homefix.pricing.cache.PricingConfigCachePort;
import com.homefix.pricing.config.PricingParametersPort;
import com.homefix.pricing.domain.PricingParameters;

/**
 * Read-through accessor and Admin writer for per-subcategory pricing parameters
 * (Requirement 6.11).
 *
 * <p>Reads consult the {@link PricingConfigCachePort} first and fall back to the
 * {@link PricingParametersPort} source of truth on a miss, repopulating the cache. Admin writes
 * persist through the source of truth and invalidate the cache so the next read repopulates —
 * guaranteeing updated parameters reach new bookings within the cache TTL (default 60 s).
 */
@Service
public class PricingConfigService {

    private final PricingConfigCachePort cache;
    private final PricingParametersPort parametersPort;

    public PricingConfigService(PricingConfigCachePort cache, PricingParametersPort parametersPort) {
        this.cache = cache;
        this.parametersPort = parametersPort;
    }

    /**
     * Resolves the effective pricing parameters for a subcategory, read-through the cache.
     *
     * @throws PricingException if no parameters are configured for the subcategory.
     */
    public PricingParameters requireParameters(UUID subcategoryId) {
        return cache.get(subcategoryId).orElseGet(() -> {
            PricingParameters loaded = parametersPort.findBySubcategoryId(subcategoryId)
                    .orElseThrow(() -> new PricingException(HttpStatus.NOT_FOUND,
                            "PRICING_PARAMETERS_NOT_FOUND",
                            "No pricing parameters configured for subcategory " + subcategoryId));
            cache.put(loaded);
            return loaded;
        });
    }

    /**
     * Admin update of pricing parameters. Persists through the source of truth and invalidates
     * the cache entry so the change is reflected on the next read within the TTL bound
     * (Requirement 6.11).
     */
    public PricingParameters updateParameters(PricingParameters parameters) {
        PricingParameters saved = parametersPort.save(parameters);
        cache.invalidate(saved.subcategoryId());
        return saved;
    }
}
