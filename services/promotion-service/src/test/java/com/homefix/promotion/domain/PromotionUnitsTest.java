package com.homefix.promotion.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.homefix.promotion.api.dto.CouponResponse;
import com.homefix.promotion.config.PromotionProperties;
import com.homefix.promotion.service.CouponException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Unit tests for the pure discount computation (Requirement 21.2), the coupon response projection,
 * the domain exception factories, and the tunable promotion properties.
 */
class PromotionUnitsTest {

    @Test
    void flatDiscount_isCappedAtOrderValue() {
        assertThat(DiscountCalculator.compute(DiscountType.FLAT, new BigDecimal("50"), null,
                new BigDecimal("200"))).isEqualByComparingTo("50.00");
        // FLAT discount never exceeds the order value.
        assertThat(DiscountCalculator.compute(DiscountType.FLAT, new BigDecimal("50"), null,
                new BigDecimal("30"))).isEqualByComparingTo("30.00");
    }

    @Test
    void percentageDiscount_appliesRateThenCap() {
        // 15% of 200 = 30, under the 40 cap.
        assertThat(DiscountCalculator.compute(DiscountType.PERCENTAGE, new BigDecimal("15"),
                new BigDecimal("40"), new BigDecimal("200"))).isEqualByComparingTo("30.00");
        // 25% of 400 = 100, capped at 40.
        assertThat(DiscountCalculator.compute(DiscountType.PERCENTAGE, new BigDecimal("25"),
                new BigDecimal("40"), new BigDecimal("400"))).isEqualByComparingTo("40.00");
    }

    @Test
    void percentageDiscount_withoutCap_isRejected() {
        assertThatThrownBy(() -> DiscountCalculator.compute(DiscountType.PERCENTAGE,
                new BigDecimal("10"), null, new BigDecimal("100")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maximum discount cap");
    }

    @Test
    void compute_rejectsNullTypeNonPositiveValueAndNegativeOrder() {
        assertThatThrownBy(() -> DiscountCalculator.compute(null, BigDecimal.TEN, null, BigDecimal.TEN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DiscountCalculator.compute(DiscountType.FLAT, BigDecimal.ZERO, null,
                BigDecimal.TEN)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DiscountCalculator.compute(DiscountType.FLAT, BigDecimal.TEN, null,
                new BigDecimal("-1"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void couponResponse_mapsAllFields() {
        Coupon coupon = Coupon.create("SAVE50", DiscountType.FLAT, new BigDecimal("50"),
                new BigDecimal("100"), null, LocalDate.now().minusDays(1), LocalDate.now().plusDays(30),
                2, 3);
        CouponResponse response = CouponResponse.from(coupon);
        assertThat(response.code()).isEqualTo("SAVE50");
        assertThat(response.discountType()).isEqualTo(DiscountType.FLAT);
        assertThat(response.perUserLimit()).isEqualTo(2);
        assertThat(response.totalLimit()).isEqualTo(3);
        assertThat(response.totalUsed()).isZero();
        assertThat(response.active()).isTrue();
    }

    @Test
    void couponException_factoriesCarryStatusAndErrorCode() {
        assertThat(CouponException.validation("x").getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(CouponException.notFound("x").getErrorCode()).isEqualTo("COUPON_NOT_FOUND");
        assertThat(CouponException.duplicateCode("x").getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(CouponException.concurrentConflict("x").getErrorCode())
                .isEqualTo("CONCURRENT_MODIFICATION");
        assertThat(CouponException.constraintViolated(
                CouponException.ConstraintCode.COUPON_EXPIRED, "expired").getStatus())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void promotionProperties_roundTrip() {
        PromotionProperties p = new PromotionProperties();
        assertThat(p.getMaxRedemptionRetries()).isEqualTo(5);
        p.setMaxRedemptionRetries(10);
        assertThat(p.getMaxRedemptionRetries()).isEqualTo(10);
    }
}
