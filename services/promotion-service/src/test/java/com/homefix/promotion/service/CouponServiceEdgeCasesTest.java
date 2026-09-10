package com.homefix.promotion.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import com.homefix.promotion.config.PromotionProperties;
import com.homefix.promotion.domain.Coupon;
import com.homefix.promotion.domain.DiscountType;
import com.homefix.promotion.service.CouponException.ConstraintCode;
import com.homefix.promotion.support.InMemoryCouponRepository;
import com.homefix.promotion.support.InMemoryCouponUsageRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;

/**
 * Edge-case unit tests for {@link CouponService} closing coverage gaps: date-window constraint
 * codes (21.2), lookup by code and not-found, activation re-enabling a coupon (21.4), and the
 * bounded optimistic-lock retry that surfaces a 409 CONCURRENT_MODIFICATION when contention cannot
 * be resolved (21.3, Property 20).
 */
class CouponServiceEdgeCasesTest {

    private static final Instant NOW = Instant.parse("2024-06-01T12:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2024, 6, 1);
    private static final UUID USER = UUID.randomUUID();

    private final InMemoryCouponRepository coupons = new InMemoryCouponRepository();
    private final InMemoryCouponUsageRepository usages = new InMemoryCouponUsageRepository();
    private final PromotionProperties props = new PromotionProperties();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private CouponService service() {
        return new CouponService(coupons, usages, new CouponCounterTransaction(coupons, usages),
                props, clock);
    }

    private CreateCouponCommand cmd(String code, LocalDate from, LocalDate to) {
        return new CreateCouponCommand(code, DiscountType.FLAT, new BigDecimal("50"),
                new BigDecimal("100"), null, from, to, 2, 3);
    }

    @Test
    void validate_notStartedCoupon_yieldsCouponNotStarted() {
        CouponService service = service();
        service.createCoupon(cmd("FUTURE", TODAY.plusDays(5), TODAY.plusDays(30)));

        assertThatThrownBy(() -> service.validate("FUTURE", USER, new BigDecimal("200")))
                .isInstanceOf(CouponException.class)
                .satisfies(e -> assertThat(((CouponException) e).getErrorCode())
                        .isEqualTo(ConstraintCode.COUPON_NOT_STARTED.name()));
    }

    @Test
    void validate_expiredCoupon_yieldsCouponExpired() {
        CouponService service = service();
        service.createCoupon(cmd("PAST", TODAY.minusDays(30), TODAY.minusDays(1)));

        assertThatThrownBy(() -> service.validate("PAST", USER, new BigDecimal("200")))
                .isInstanceOf(CouponException.class)
                .satisfies(e -> assertThat(((CouponException) e).getErrorCode())
                        .isEqualTo(ConstraintCode.COUPON_EXPIRED.name()));
    }

    @Test
    void getCouponByCode_unknown_isNotFound() {
        assertThatThrownBy(() -> service().getCouponByCode("NOPE"))
                .isInstanceOf(CouponException.class)
                .satisfies(e -> assertThat(((CouponException) e).getErrorCode())
                        .isEqualTo("COUPON_NOT_FOUND"));
    }

    @Test
    void activateCoupon_reenablesADeactivatedCoupon() {
        CouponService service = service();
        Coupon coupon = service.createCoupon(cmd("SAVE50", TODAY.minusDays(1), TODAY.plusDays(30)));
        service.deactivateCoupon(coupon.getId());

        Coupon reactivated = service.activateCoupon(coupon.getId());

        assertThat(reactivated.isActive()).isTrue();
    }

    @Test
    void redeem_exhaustsRetriesUnderPersistentContention_thenReports409() {
        CouponCounterTransaction alwaysContended =
                new CouponCounterTransaction(coupons, usages) {
                    @Override
                    public void applyRedemption(UUID couponId, UUID userId) {
                        throw new OptimisticLockingFailureException("contended");
                    }
                };
        props.setMaxRedemptionRetries(3);
        CouponService service = new CouponService(coupons, usages, alwaysContended, props, clock);
        service.createCoupon(cmd("RUSH", TODAY.minusDays(1), TODAY.plusDays(30)));

        assertThatThrownBy(() -> service.redeem("RUSH", USER))
                .isInstanceOf(CouponException.class)
                .satisfies(e -> assertThat(((CouponException) e).getErrorCode())
                        .isEqualTo("CONCURRENT_MODIFICATION"));
    }

    @Test
    void cancelRedemption_exhaustsRetriesUnderPersistentContention_thenReports409() {
        CouponCounterTransaction alwaysContended =
                new CouponCounterTransaction(coupons, usages) {
                    @Override
                    public void applyCancellation(UUID couponId, UUID userId) {
                        throw new OptimisticLockingFailureException("contended");
                    }
                };
        props.setMaxRedemptionRetries(2);
        CouponService service = new CouponService(coupons, usages, alwaysContended, props, clock);
        service.createCoupon(cmd("RUSH2", TODAY.minusDays(1), TODAY.plusDays(30)));

        assertThatThrownBy(() -> service.cancelRedemption("RUSH2", USER))
                .isInstanceOf(CouponException.class)
                .satisfies(e -> assertThat(((CouponException) e).getErrorCode())
                        .isEqualTo("CONCURRENT_MODIFICATION"));
    }
}
