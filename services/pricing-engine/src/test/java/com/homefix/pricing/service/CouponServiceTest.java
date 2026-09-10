package com.homefix.pricing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.homefix.pricing.coupon.CouponUsagePort;
import com.homefix.pricing.domain.Coupon;

/**
 * Unit tests for coupon validation (Requirement 6.10). Each constraint violation must surface a
 * descriptive error identifying the violated constraint. Usage lookups go through the mockable
 * {@link CouponUsagePort}; the {@link Clock} is fixed for deterministic date-range checks.
 */
@ExtendWith(MockitoExtension.class)
class CouponServiceTest {

    private static final Instant NOW = Instant.parse("2024-06-15T12:00:00Z");
    private static final UUID USER = UUID.randomUUID();

    @Mock
    private CouponUsagePort usagePort;

    private CouponService service() {
        return new CouponService(usagePort, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private Coupon coupon(boolean active, Instant from, Instant until, String minOrder,
                          String amount, Integer perUser, Integer total) {
        return new Coupon("SAVE10", active, from, until,
                minOrder == null ? null : new BigDecimal(minOrder),
                amount == null ? null : new BigDecimal(amount),
                null, perUser, total);
    }

    @Test
    void validCoupon_returnsFlatDiscount() {
        Coupon c = coupon(true, NOW.minusSeconds(100), NOW.plusSeconds(100), "50", "10", 2, 100);
        when(usagePort.totalRedemptions("SAVE10")).thenReturn(5);
        when(usagePort.userRedemptions("SAVE10", USER)).thenReturn(1);

        BigDecimal discount = service().validateAndComputeDiscount(c, new BigDecimal("100"), USER);

        assertThat(discount).isEqualByComparingTo("10");
    }

    @Test
    void percentageCoupon_computesFraction() {
        Coupon c = new Coupon("PCT", true, null, null, null, null, new BigDecimal("0.10"), null, null);

        BigDecimal discount = service().validateAndComputeDiscount(c, new BigDecimal("200"), USER);

        assertThat(discount).isEqualByComparingTo("20.0");
    }

    @Test
    void inactiveCoupon_isRejected() {
        Coupon c = coupon(false, null, null, null, "10", null, null);

        assertThatThrownBy(() -> service().validateAndComputeDiscount(c, new BigDecimal("100"), USER))
                .isInstanceOf(PricingException.class)
                .satisfies(ex -> assertThat(((PricingException) ex).getErrorCode()).isEqualTo("COUPON_INACTIVE"));
    }

    @Test
    void notYetValidCoupon_isRejected() {
        Coupon c = coupon(true, NOW.plusSeconds(3600), NOW.plusSeconds(7200), null, "10", null, null);

        assertThatThrownBy(() -> service().validateAndComputeDiscount(c, new BigDecimal("100"), USER))
                .isInstanceOf(PricingException.class)
                .satisfies(ex -> assertThat(((PricingException) ex).getErrorCode()).isEqualTo("COUPON_NOT_YET_VALID"));
    }

    @Test
    void expiredCoupon_isRejected() {
        Coupon c = coupon(true, NOW.minusSeconds(7200), NOW.minusSeconds(3600), null, "10", null, null);

        assertThatThrownBy(() -> service().validateAndComputeDiscount(c, new BigDecimal("100"), USER))
                .isInstanceOf(PricingException.class)
                .satisfies(ex -> assertThat(((PricingException) ex).getErrorCode()).isEqualTo("COUPON_EXPIRED"));
    }

    @Test
    void belowMinimumOrderValue_isRejected() {
        Coupon c = coupon(true, null, null, "100", "10", null, null);

        assertThatThrownBy(() -> service().validateAndComputeDiscount(c, new BigDecimal("99.99"), USER))
                .isInstanceOf(PricingException.class)
                .satisfies(ex -> {
                    PricingException pe = (PricingException) ex;
                    assertThat(pe.getErrorCode()).isEqualTo("COUPON_MIN_ORDER_NOT_MET");
                    assertThat(pe.getMessage()).contains("100");
                });
    }

    @Test
    void totalUsageLimitReached_isRejected() {
        Coupon c = coupon(true, null, null, null, "10", null, 100);
        when(usagePort.totalRedemptions("SAVE10")).thenReturn(100);

        assertThatThrownBy(() -> service().validateAndComputeDiscount(c, new BigDecimal("100"), USER))
                .isInstanceOf(PricingException.class)
                .satisfies(ex -> assertThat(((PricingException) ex).getErrorCode())
                        .isEqualTo("COUPON_TOTAL_LIMIT_REACHED"));
    }

    @Test
    void perUserLimitReached_isRejected() {
        Coupon c = coupon(true, null, null, null, "10", 3, null);
        when(usagePort.userRedemptions("SAVE10", USER)).thenReturn(3);

        assertThatThrownBy(() -> service().validateAndComputeDiscount(c, new BigDecimal("100"), USER))
                .isInstanceOf(PricingException.class)
                .satisfies(ex -> assertThat(((PricingException) ex).getErrorCode())
                        .isEqualTo("COUPON_PER_USER_LIMIT_REACHED"));
    }

    @Test
    void discountNeverExceedsOrderValue() {
        Coupon c = coupon(true, null, null, null, "1000", null, null);

        BigDecimal discount = service().validateAndComputeDiscount(c, new BigDecimal("40"), USER);

        assertThat(discount).isEqualByComparingTo("40");
    }

    @Test
    void nullCoupon_yieldsZeroDiscount() {
        assertThat(service().validateAndComputeDiscount(null, new BigDecimal("100"), USER))
                .isEqualByComparingTo("0");
    }
}
