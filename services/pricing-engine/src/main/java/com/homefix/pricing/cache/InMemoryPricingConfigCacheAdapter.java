package com.homefix.pricing.cache;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.homefix.pricing.config.PricingProperties;
import com.homefix.pricing.domain.PricingParameters;

/**
 * In-memory {@link PricingConfigCachePort} used as the default when Redis is not configured
 * ({@code homefix.pricing.cache=memory}, the default). It enforces the same TTL semantics as
 * the Redis adapter so behaviour is identical in local/dev environments and in unit tests.
 *
 * <p>The clock is injectable so tests can advance time deterministically to assert the
 * 60-second TTL bound of Requirement 6.11 without sleeping.
 */
@Component
@ConditionalOnProperty(name = "homefix.pricing.cache", havingValue = "memory", matchIfMissing = true)
public class InMemoryPricingConfigCacheAdapter implements PricingConfigCachePort {

    private final PricingProperties properties;
    private final Clock clock;
    private final Map<UUID, Entry> entries = new ConcurrentHashMap<>();

    @Autowired
    public InMemoryPricingConfigCacheAdapter(PricingProperties properties) {
        this(properties, Clock.systemUTC());
    }

    /** Test/advanced constructor allowing a controllable clock. */
    public InMemoryPricingConfigCacheAdapter(PricingProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public Optional<PricingParameters> get(UUID subcategoryId) {
        Entry current = entries.get(subcategoryId);
        if (current == null) {
            return Optional.empty();
        }
        if (!Instant.now(clock).isBefore(current.expiresAt)) {
            // Expired — behave as a miss and clear the stale entry.
            entries.remove(subcategoryId, current);
            return Optional.empty();
        }
        return Optional.of(current.value);
    }

    @Override
    public void put(PricingParameters parameters) {
        Duration ttl = properties.getConfigCacheTtl();
        Instant expiresAt = Instant.now(clock).plus(ttl);
        entries.put(parameters.subcategoryId(), new Entry(parameters, expiresAt));
    }

    @Override
    public void invalidate(UUID subcategoryId) {
        entries.remove(subcategoryId);
    }

    private record Entry(PricingParameters value, Instant expiresAt) {
    }
}
