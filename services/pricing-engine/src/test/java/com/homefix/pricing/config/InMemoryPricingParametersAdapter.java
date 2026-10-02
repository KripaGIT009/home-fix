package com.homefix.pricing.config;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.homefix.pricing.domain.PricingParameters;

/**
 * Test-only in-memory {@link PricingParametersPort}.
 *
 * <p>It used to be the service's production adapter, which is why every pricing-engine restart
 * wiped the Admin parameters (CODEBASE_REVIEW 8.3). The running service now uses
 * {@code JpaPricingParametersAdapter}; this map-backed fake lives in the test sources so it can
 * never be wired into the application again.
 */
public class InMemoryPricingParametersAdapter implements PricingParametersPort {

    private final Map<UUID, PricingParameters> store = new ConcurrentHashMap<>();

    @Override
    public Optional<PricingParameters> findBySubcategoryId(UUID subcategoryId) {
        return Optional.ofNullable(store.get(subcategoryId));
    }

    /** No update timestamps here, so the order is by subcategory id for determinism. */
    @Override
    public List<PricingParameters> findAll(int limit) {
        return store.values().stream()
                .sorted(java.util.Comparator.comparing(PricingParameters::subcategoryId))
                .limit(limit)
                .toList();
    }

    @Override
    public PricingParameters save(PricingParameters parameters) {
        store.put(parameters.subcategoryId(), parameters);
        return parameters;
    }
}
