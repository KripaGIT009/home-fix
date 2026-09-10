package com.homefix.catalog.cache;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.homefix.catalog.api.dto.CategoryView;
import com.homefix.catalog.config.CatalogProperties;

/**
 * In-memory {@link CatalogCachePort} used as the default when Redis is not configured
 * ({@code homefix.catalog.cache=memory}, the default). It enforces the same TTL semantics as
 * the Redis adapter so behaviour is identical in local/dev environments and in unit tests.
 *
 * <p>The clock is injectable so tests can advance time deterministically to assert the
 * 300-second TTL bound of Requirement 3.8 without sleeping.
 */
@Component
@ConditionalOnProperty(name = "homefix.catalog.cache", havingValue = "memory", matchIfMissing = true)
public class InMemoryCatalogCacheAdapter implements CatalogCachePort {

    private final CatalogProperties properties;
    private final Clock clock;
    private final AtomicReference<Entry> entry = new AtomicReference<>();

    @Autowired
    public InMemoryCatalogCacheAdapter(CatalogProperties properties) {
        this(properties, Clock.systemUTC());
    }

    /** Test/advanced constructor allowing a controllable clock. */
    public InMemoryCatalogCacheAdapter(CatalogProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public Optional<List<CategoryView>> getActiveCatalog() {
        Entry current = entry.get();
        if (current == null) {
            return Optional.empty();
        }
        if (!Instant.now(clock).isBefore(current.expiresAt)) {
            // Expired — behave as a miss and clear the stale entry.
            entry.compareAndSet(current, null);
            return Optional.empty();
        }
        return Optional.of(current.value);
    }

    @Override
    public void putActiveCatalog(List<CategoryView> catalog) {
        Duration ttl = properties.getCacheTtl();
        Instant expiresAt = Instant.now(clock).plus(ttl);
        entry.set(new Entry(List.copyOf(catalog), expiresAt));
    }

    @Override
    public void invalidate() {
        entry.set(null);
    }

    private record Entry(List<CategoryView> value, Instant expiresAt) {
    }
}
