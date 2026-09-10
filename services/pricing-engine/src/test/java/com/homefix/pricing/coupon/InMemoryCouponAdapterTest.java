package com.homefix.pricing.coupon;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the default in-memory coupon adapter used in local/dev and tests. With an
 * empty store, lookups miss and redemption counts default to zero so the coupon flow is
 * exercisable without a database (Requirement 6.10).
 */
class InMemoryCouponAdapterTest {

    private final InMemoryCouponAdapter adapter = new InMemoryCouponAdapter();

    @Test
    void findByCodeMissesForUnknownCode() {
        assertThat(adapter.findByCode("NOPE")).isEmpty();
    }

    @Test
    void totalRedemptionsDefaultsToZero() {
        assertThat(adapter.totalRedemptions("SAVE10")).isZero();
    }

    @Test
    void userRedemptionsDefaultsToZero() {
        assertThat(adapter.userRedemptions("SAVE10", UUID.randomUUID())).isZero();
    }
}
