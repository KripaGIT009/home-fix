package com.homefix.catalog.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.homefix.catalog.api.dto.CategoryView;
import com.homefix.catalog.config.CatalogProperties;

/**
 * TTL-compliance tests for the read-through catalog cache (Requirement 3.8): served data must
 * reflect the database state as of no more than 300 seconds ago. A mutable, controllable clock
 * lets us advance time deterministically without sleeping.
 */
class InMemoryCatalogCacheAdapterTest {

    /** A test clock whose "now" can be advanced on demand. */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private List<CategoryView> sampleCatalog() {
        return List.of(new CategoryView(UUID.randomUUID(), "Plumbing", "d", "i", 1, List.of()));
    }

    @Test
    void entryIsServedWithinTtl() {
        MutableClock clock = new MutableClock(Instant.parse("2024-01-01T00:00:00Z"));
        CatalogProperties props = new CatalogProperties();
        InMemoryCatalogCacheAdapter cache = new InMemoryCatalogCacheAdapter(props, clock);

        cache.putActiveCatalog(sampleCatalog());

        // 299 seconds later — still within the 300 s TTL, so it is a hit.
        clock.advance(Duration.ofSeconds(299));
        assertThat(cache.getActiveCatalog()).isPresent();
    }

    @Test
    void entryExpiresAfterTtlBound() {
        MutableClock clock = new MutableClock(Instant.parse("2024-01-01T00:00:00Z"));
        CatalogProperties props = new CatalogProperties();
        InMemoryCatalogCacheAdapter cache = new InMemoryCatalogCacheAdapter(props, clock);

        cache.putActiveCatalog(sampleCatalog());

        // Exactly at the 300 s bound the entry is considered expired (miss), so a fresh read
        // from the database is forced — data is never older than 300 s (Requirement 3.8).
        clock.advance(props.getCacheTtl());
        assertThat(cache.getActiveCatalog()).isEmpty();
    }

    @Test
    void entryExpiresWellBeyondTtl() {
        MutableClock clock = new MutableClock(Instant.parse("2024-01-01T00:00:00Z"));
        CatalogProperties props = new CatalogProperties();
        InMemoryCatalogCacheAdapter cache = new InMemoryCatalogCacheAdapter(props, clock);

        cache.putActiveCatalog(sampleCatalog());
        clock.advance(Duration.ofSeconds(600));
        assertThat(cache.getActiveCatalog()).isEmpty();
    }

    @Test
    void invalidateClearsEntryImmediately() {
        MutableClock clock = new MutableClock(Instant.parse("2024-01-01T00:00:00Z"));
        CatalogProperties props = new CatalogProperties();
        InMemoryCatalogCacheAdapter cache = new InMemoryCatalogCacheAdapter(props, clock);

        cache.putActiveCatalog(sampleCatalog());
        cache.invalidate();

        assertThat(cache.getActiveCatalog()).isEmpty();
    }

    @Test
    void configuredTtlNeverExceeds300SecondsByDefault() {
        // Guard against accidental relaxation of the freshness bound (Requirement 3.8).
        assertThat(new CatalogProperties().getCacheTtl()).isLessThanOrEqualTo(Duration.ofSeconds(300));
    }
}
