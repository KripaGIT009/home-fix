package com.homefix.promotion.service;

import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.homefix.promotion.domain.Coupon;
import com.homefix.promotion.domain.CouponCounters;
import com.homefix.promotion.domain.CouponRepository;
import com.homefix.promotion.domain.CouponUsage;
import com.homefix.promotion.domain.CouponUsageRepository;

/**
 * The single, atomic counter mutations that back redemption (Requirement 21.3, Property 20) and
 * cancellation decrement (Requirement 21.5, Property 21).
 *
 * <p>Each method runs in its own {@code SERIALIZABLE} transaction. Combined with the optimistic
 * {@code @Version} columns on {@link Coupon} and {@link CouponUsage}, this guarantees that two
 * concurrent redemptions cannot both read the same counter and both write an increment: the second
 * commit fails with an optimistic-lock exception, which {@code CouponService} retries. The net
 * effect is that the total and per-user counters can never exceed their configured limits,
 * regardless of the number of concurrent requests.
 *
 * <p>This is a separate bean (rather than a self-invoked method on {@code CouponService}) so the
 * transactional proxy actually applies to each attempt.
 */
@Component
public class CouponCounterTransaction {

    private final CouponRepository couponRepository;
    private final CouponUsageRepository usageRepository;

    public CouponCounterTransaction(CouponRepository couponRepository,
                                    CouponUsageRepository usageRepository) {
        this.couponRepository = couponRepository;
        this.usageRepository = usageRepository;
    }

    /**
     * Attempts one redemption: re-checks the coupon's active status and both counters, then
     * increments the total and per-user counters together (Requirement 21.3). The active-status
     * re-check inside the transaction means a coupon deactivated after the caller's validation but
     * before commit is still blocked here (Requirement 21.4), while a redemption whose validation
     * and increment both complete before deactivation is honoured.
     *
     * @throws CouponException 404 if the coupon vanished, 422 if a limit is now reached.
     */
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public void applyRedemption(UUID couponId, UUID userId) {
        Coupon coupon = couponRepository.findById(couponId)
                .orElseThrow(() -> CouponException.notFound("Coupon " + couponId + " not found"));

        if (!coupon.isActive()) {
            throw CouponException.constraintViolated(CouponException.ConstraintCode.COUPON_INACTIVE,
                    "Coupon is no longer active and cannot be redeemed");
        }

        CouponUsage usage = usageRepository.findByCouponIdAndUserId(couponId, userId)
                .orElseGet(() -> CouponUsage.forUser(couponId, userId));

        if (!CouponCounters.canRedeem(coupon.getTotalUsed(), coupon.getTotalLimit(),
                usage.getUsageCount(), coupon.getPerUserLimit())) {
            if (usage.getUsageCount() >= coupon.getPerUserLimit()) {
                throw CouponException.constraintViolated(
                        CouponException.ConstraintCode.PER_USER_LIMIT_REACHED,
                        "Per-user usage limit reached for this coupon");
            }
            throw CouponException.constraintViolated(
                    CouponException.ConstraintCode.TOTAL_LIMIT_REACHED,
                    "Total usage limit reached for this coupon");
        }

        // Both counters move together — never one without the other.
        coupon.incrementTotalUsed();
        usage.increment(coupon.getPerUserLimit());

        usageRepository.save(usage);
        couponRepository.save(coupon);
    }

    /**
     * Attempts one cancellation decrement: decrements the total and per-user counters together to
     * restore the coupon availability (Requirement 21.5, Property 21). Idempotent-safe in that
     * counters never go below zero.
     *
     * @throws CouponException 404 if the coupon or the user's usage row is missing.
     */
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public void applyCancellation(UUID couponId, UUID userId) {
        Coupon coupon = couponRepository.findById(couponId)
                .orElseThrow(() -> CouponException.notFound("Coupon " + couponId + " not found"));
        CouponUsage usage = usageRepository.findByCouponIdAndUserId(couponId, userId)
                .orElseThrow(() -> CouponException.notFound(
                        "No redemption found to cancel for this coupon and user"));

        coupon.decrementTotalUsed();
        usage.decrement();

        usageRepository.save(usage);
        couponRepository.save(coupon);
    }
}
