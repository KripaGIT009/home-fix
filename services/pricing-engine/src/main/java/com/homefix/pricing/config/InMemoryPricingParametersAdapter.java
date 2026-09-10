package com.homefix.pricing.config;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.homefix.pricing.domain.PricingParameters;

/**
 * Default in-memory {@link PricingParametersPort} so the service boots and is usable in
 * local/dev environments and tests without a provisioned database. In production this is
 * replaced by a JPA-backed adapter reading the Admin-managed pricing-parameters table.
 */
@Component
public class InMemoryPricingParametersAdapter implements PricingParametersPort {

    private final Map<UUID, PricingParameters> store = new ConcurrentHashMap<>();

    @Override
    public Optional<PricingParameters> findBySubcategoryId(UUID subcategoryId) {
        return Optional.ofNullable(store.get(subcategoryId));
    }

    @Override
    public PricingParameters save(PricingParameters parameters) {
        store.put(parameters.subcategoryId(), parameters);
        return parameters;
    }
}
