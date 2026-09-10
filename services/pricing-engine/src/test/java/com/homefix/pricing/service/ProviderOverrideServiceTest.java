package com.homefix.pricing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static com.homefix.pricing.support.TestData.baseParams;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import com.homefix.pricing.domain.PricingParameters;

/**
 * Unit tests for provider-specific override validation (Requirement 6.12, Property 7). The
 * override is rejected if and only if the proposed price is below the floor or above the
 * ceiling; otherwise accepted. Floor/ceiling in {@code baseParams} are 10.00 / 500.00.
 */
class ProviderOverrideServiceTest {

    private final ProviderOverrideService service = new ProviderOverrideService();
    private final PricingParameters params = baseParams("100.00");

    @Test
    void priceWithinRange_isAccepted() {
        assertThat(service.validateOverride(new BigDecimal("250.00"), params))
                .isEqualByComparingTo("250.00");
    }

    @Test
    void priceAtFloor_isAccepted() {
        assertThat(service.validateOverride(new BigDecimal("10.00"), params))
                .isEqualByComparingTo("10.00");
    }

    @Test
    void priceAtCeiling_isAccepted() {
        assertThat(service.validateOverride(new BigDecimal("500.00"), params))
                .isEqualByComparingTo("500.00");
    }

    @Test
    void priceBelowFloor_isRejectedWithRange() {
        assertThatThrownBy(() -> service.validateOverride(new BigDecimal("9.99"), params))
                .isInstanceOf(PricingException.class)
                .satisfies(ex -> {
                    PricingException pe = (PricingException) ex;
                    assertThat(pe.getErrorCode()).isEqualTo("OVERRIDE_OUT_OF_RANGE");
                    assertThat(pe.getDetails()).contains("floor=10.00", "ceiling=500.00", "proposed=9.99");
                });
    }

    @Test
    void priceAboveCeiling_isRejectedWithRange() {
        assertThatThrownBy(() -> service.validateOverride(new BigDecimal("500.01"), params))
                .isInstanceOf(PricingException.class)
                .satisfies(ex -> assertThat(((PricingException) ex).getErrorCode())
                        .isEqualTo("OVERRIDE_OUT_OF_RANGE"));
    }
}
