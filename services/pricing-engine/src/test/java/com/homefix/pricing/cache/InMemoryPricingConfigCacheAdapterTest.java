package com.homefix.pricing.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static com.homefix.pricing.support.TestData.SUBCATEGORY;
import static com.homefix.pricing.support.TestData.baseParams;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import com.homefix.pricing.config.PricingProperties;
import com.homefix.pricing.domain.PricingParameters;

/**
 * Unit tests for the in-memory config cache TTL semantics (Requirement 6.11). A mutable clock
 * lets us advance time deterministically to assert the 60-second expiry without sleeping.
 */
class InMemoryPricingConfigCacheAdapterTest {

    /** A test clock we can advance manually. */
    private static final class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant start) {
            this.instant = start;
        }

        void advance(Duration d) {
            instant = instant.plus(d);
        }

        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }

    @Test
    void entryIsHitBeforeTtlAndMissesAfterTtl() {
        PricingProperties props = new PricingProperties(); // 60s TTL default
        MutableClock clock = new MutableClock(Instant.parse("2024-06-15T12:00:00Z"));
        InMemoryPricingConfigCacheAdapter cache = new InMemoryPricingConfigCacheAdapter(props, clock);

        cache.put(baseParams("100.00"));

        // Within TTL: hit.
        clock.advance(Duration.ofSeconds(59));
        assertThat(cache.get(SUBCATEGORY)).isPresent();

        // At/after TTL: miss (parameters must be reloaded => Admin change would take effect).
        clock.advance(Duration.ofSeconds(1));
        assertThat(cache.get(SUBCATEGORY)).isEmpty();
    }

    @Test
    void invalidateForcesReload() {
        PricingProperties props = new PricingProperties();
        MutableClock clock = new MutableClock(Instant.parse("2024-06-15T12:00:00Z"));
        InMemoryPricingConfigCacheAdapter cache = new InMemoryPricingConfigCacheAdapter(props, clock);

        cache.put(baseParams("100.00"));
        assertThat(cache.get(SUBCATEGORY)).isPresent();

        cache.invalidate(SUBCATEGORY);
        assertThat(cache.get(SUBCATEGORY)).isEmpty();
    }

    @Test
    void freshPutReflectsUpdatedParameters() {
        PricingProperties props = new PricingProperties();
        MutableClock clock = new MutableClock(Instant.parse("2024-06-15T12:00:00Z"));
        InMemoryPricingConfigCacheAdapter cache = new InMemoryPricingConfigCacheAdapter(props, clock);

        cache.put(baseParams("100.00"));
        cache.invalidate(SUBCATEGORY);
        cache.put(baseParams("150.00"));

        PricingParameters got = cache.get(SUBCATEGORY).orElseThrow();
        assertThat(got.basePrice()).isEqualByComparingTo("150.00");
    }
}
