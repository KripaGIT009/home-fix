package com.homefix.pricing.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.homefix.pricing.coupon.CouponUsagePort;
import com.homefix.pricing.domain.Coupon;

/**
 * Coupon validation and discount computation (Requirement 6.10).
 *
 * <p>Validates, in order, the coupon's active status, valid date range, minimum order value,
 * per-user usage limit and total usage limit. On any violation a {@link PricingException} is
 * thrown carrying a stable error code and a descriptive message identifying the violated
 * constraint. Usage-limit lookups go through the mockable {@link CouponUsagePort}; the
 * {@link Clock} is injectable so date-range checks are deterministic in tests.
 */
@Service
public class CouponService {

    private final CouponUsagePort usagePort;
    private final Clock clock;

    @Autowired
    public CouponService(CouponUsagePort usagePort) {
        this(usagePort, Clock.systemUTC());
    }

    public CouponService(CouponUsagePort usagePort, Clock clock) {
        this.usagePort = usagePort;
        this.clock = clock;
    }

    /**
     * Validates the coupon against the order subtotal and user, and returns the discount amount
     * to apply. Throws {@link PricingException} on any violated constraint.
     *
     * @param coupon       the coupon to apply
     * @param orderValue   the pre-coupon order subtotal used for the minimum-order check
     * @param userId       the redeeming user (may be null when no per-user limit applies)
     */
    public BigDecimal validateAndComputeDiscount(Coupon coupon, BigDecimal orderValue, UUID userId) {
        if (coupon == null) {
            return BigDecimal.ZERO;
        }
        Instant now = Instant.now(clock);

        if (!coupon.active()) {
            throw PricingException.coupon("COUPON_INACTIVE",
                    "Coupon '" + coupon.code() + "' is not active");
        }
        if (coupon.validFrom() != null && now.isBefore(coupon.validFrom())) {
            throw PricingException.coupon("COUPON_NOT_YET_VALID",
                    "Coupon '" + coupon.code() + "' is not valid until " + coupon.validFrom());
        }
        if (coupon.validUntil() != null && now.isAfter(coupon.validUntil())) {
            throw PricingException.coupon("COUPON_EXPIRED",
                    "Coupon '" + coupon.code() + "' expired on " + coupon.validUntil());
        }
        BigDecimal order = orderValue == null ? BigDecimal.ZERO : orderValue;
        if (coupon.minOrderValue() != null && order.compareTo(coupon.minOrderValue()) < 0) {
            throw PricingException.coupon("COUPON_MIN_ORDER_NOT_MET",
                    "Coupon '" + coupon.code() + "' requires a minimum order value of "
                            + coupon.minOrderValue() + " (order value was " + order + ")");
        }
        if (coupon.totalLimit() != null
                && usagePort.totalRedemptions(coupon.code()) >= coupon.totalLimit()) {
            throw PricingException.coupon("COUPON_TOTAL_LIMIT_REACHED",
                    "Coupon '" + coupon.code() + "' has reached its total usage limit of "
                            + coupon.totalLimit());
        }
        if (coupon.perUserLimit() != null && userId != null
                && usagePort.userRedemptions(coupon.code(), userId) >= coupon.perUserLimit()) {
            throw PricingException.coupon("COUPON_PER_USER_LIMIT_REACHED",
                    "Coupon '" + coupon.code() + "' has reached its per-user usage limit of "
                            + coupon.perUserLimit());
        }

        return computeDiscount(coupon, order);
    }

    private BigDecimal computeDiscount(Coupon coupon, BigDecimal order) {
        BigDecimal discount;
        if (coupon.isPercentage()) {
            discount = order.multiply(coupon.percentage());
        } else {
            discount = coupon.amount() == null ? BigDecimal.ZERO : coupon.amount();
        }
        // A coupon never discounts more than the order value.
        if (discount.compareTo(order) > 0) {
            discount = order;
        }
        return discount.signum() < 0 ? BigDecimal.ZERO : discount;
    }
}
