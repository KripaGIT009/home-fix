package com.homefix.promotion.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.homefix.promotion.config.PromotionProperties;
import com.homefix.promotion.domain.Coupon;
import com.homefix.promotion.domain.CouponRepository;
import com.homefix.promotion.domain.CouponStatus;
import com.homefix.promotion.domain.CouponUsage;
import com.homefix.promotion.domain.CouponUsageRepository;
import com.homefix.promotion.domain.DiscountCalculator;
import com.homefix.promotion.service.CouponException.ConstraintCode;

/**
 * Coupon and promotion business logic (Requirement 21).
 *
 * <p>Responsibilities: Admin coupon CRUD with attribute validation (21.1), checkout-time
 * validation returning the applicable discount or a descriptive constraint violation (21.2),
 * atomic redemption that increments the total and per-user counters without over-redemption under
 * concurrency (21.3, Property 20), immediate deactivation (21.4), and atomic counter decrement on
 * cancellation before payment capture (21.5, Property 21).
 *
 * <p>Concurrency-safety approach: the actual counter mutation runs in a {@code SERIALIZABLE}
 * transaction inside {@link CouponCounterTransaction}, guarded by optimistic {@code @Version}
 * columns. This method wraps each attempt in a bounded retry loop; a losing concurrent writer sees
 * an {@link OptimisticLockingFailureException}, reloads, and retries, so committed increments never
 * exceed the configured limits.
 *
 * <p>Per Requirement 26.4, logs reference coupon codes and IDs only — never customer PII.
 */
@Service
public class CouponService {

    private static final Logger log = LoggerFactory.getLogger(CouponService.class);

    /**
     * Upper bound on the Admin Portal coupon list (Requirement 19.2). The portal's table takes a
     * bare array with no paging, so the newest {@value} coupons are returned.
     */
    static final int ADMIN_LIST_LIMIT = 200;

    private final CouponRepository couponRepository;
    private final CouponUsageRepository usageRepository;
    private final CouponCounterTransaction counterTransaction;
    private final PromotionProperties props;
    private final Clock clock;

    public CouponService(CouponRepository couponRepository,
                         CouponUsageRepository usageRepository,
                         CouponCounterTransaction counterTransaction,
                         PromotionProperties props,
                         Clock clock) {
        this.couponRepository = couponRepository;
        this.usageRepository = usageRepository;
        this.counterTransaction = counterTransaction;
        this.props = props;
        this.clock = clock;
    }

    // ===================== Admin CRUD (Requirement 21.1) =====================

    /**
     * Creates a coupon (Requirement 21.1). Attribute validation is enforced by the domain factory;
     * a duplicate code (case-insensitive) is rejected with 409.
     */
    @Transactional
    public Coupon createCoupon(CreateCouponCommand cmd) {
        String normalised = Coupon.normaliseCode(cmd.code());
        if (normalised != null && couponRepository.existsByCode(normalised)) {
            throw CouponException.duplicateCode("A coupon with code '" + normalised + "' already exists");
        }
        Coupon coupon = Coupon.create(cmd.code(), cmd.discountType(), cmd.discountValue(),
                cmd.minOrderValue(), cmd.maxDiscountCap(), cmd.validFrom(), cmd.expiryDate(),
                cmd.perUserLimit(), cmd.totalLimit());
        Coupon saved = couponRepository.save(coupon);
        log.info("Created coupon {} code {}", saved.getId(), saved.getCode());
        return saved;
    }

    @Transactional(readOnly = true)
    public Coupon getCoupon(UUID couponId) {
        return require(couponId);
    }

    @Transactional(readOnly = true)
    public Coupon getCouponByCode(String code) {
        return couponRepository.findByCode(Coupon.normaliseCode(code))
                .orElseThrow(() -> CouponException.notFound("Coupon with code '" + code + "' not found"));
    }

    /** Immediately prevents further redemptions of the coupon (Requirement 21.4). */
    @Transactional
    public Coupon deactivateCoupon(UUID couponId) {
        Coupon coupon = require(couponId);
        coupon.deactivate();
        Coupon saved = couponRepository.save(coupon);
        log.info("Deactivated coupon {} code {}", saved.getId(), saved.getCode());
        return saved;
    }

    @Transactional
    public Coupon activateCoupon(UUID couponId) {
        Coupon coupon = require(couponId);
        coupon.activate();
        return couponRepository.save(coupon);
    }

    // ===================== Admin Portal (Requirement 19.2) =====================

    /** The Admin Portal coupon list: newest first, bounded by {@link #ADMIN_LIST_LIMIT}. */
    @Transactional(readOnly = true)
    public List<Coupon> listForAdmin() {
        return couponRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, ADMIN_LIST_LIMIT));
    }

    /**
     * The coupon's Admin Portal status today, on the same clock the checkout validity-window check
     * uses (Requirement 21.2), so the portal never shows ACTIVE for a coupon checkout calls expired.
     */
    public CouponStatus statusOf(Coupon coupon) {
        return coupon.statusOn(LocalDate.now(clock));
    }

    // ===================== Validation (Requirement 21.2) =====================

    /**
     * Validates a coupon against all checkout constraints (active status, date range, minimum order
     * value, per-user usage limit, total usage limit) and returns the applicable discount amount
     * (Requirement 21.2). No counter is mutated here.
     *
     * <p>{@code userId} may be {@code null} only on the service-to-service quote path
     * ({@code InternalCouponController}), for an estimate requested before the customer is known.
     * The per-user limit is then not evaluated; it is still enforced when the coupon is redeemed.
     * The customer-facing {@code POST /coupons/validate} always requires a user.
     *
     * @throws CouponException 422 with a constraint-specific error code on the first violation.
     */
    @Transactional(readOnly = true)
    public CouponValidationResult validate(String code, UUID userId, BigDecimal orderValue) {
        Coupon coupon = getCouponByCode(code);
        int userUsage = userId == null ? 0 : currentUserUsage(coupon.getId(), userId);
        LocalDate today = LocalDate.now(clock);

        Optional<ConstraintCode> violation = coupon.checkApplicability(orderValue, userUsage, today);
        if (violation.isPresent()) {
            throw CouponException.constraintViolated(violation.get(),
                    describe(violation.get(), coupon, orderValue));
        }

        BigDecimal discount = DiscountCalculator.compute(coupon.getDiscountType(),
                coupon.getDiscountValue(), coupon.getMaxDiscountCap(), orderValue);
        return new CouponValidationResult(coupon.getId(), coupon.getCode(), discount);
    }

    // ===================== Atomic redemption (Requirement 21.3, Property 20) =====================

    /**
     * Redeems the coupon for a user, atomically incrementing both the total and per-user usage
     * counters (Requirement 21.3, Property 20). Retries on optimistic-lock contention up to the
     * configured maximum so concurrent redemptions serialise without over-redemption.
     *
     * @throws CouponException 422 if a limit is reached, 409 if contention could not be resolved.
     */
    public Coupon redeem(String code, UUID userId) {
        Coupon coupon = getCouponByCode(code);
        UUID couponId = coupon.getId();

        int attempts = 0;
        int maxAttempts = Math.max(1, props.getMaxRedemptionRetries());
        while (true) {
            attempts++;
            try {
                counterTransaction.applyRedemption(couponId, userId);
                Coupon updated = require(couponId);
                log.info("Redeemed coupon {} code {} (attempt {}); totalUsed now {}",
                        couponId, updated.getCode(), attempts, updated.getTotalUsed());
                return updated;
            } catch (OptimisticLockingFailureException e) {
                if (attempts >= maxAttempts) {
                    log.warn("Redemption of coupon {} abandoned after {} contended attempts",
                            couponId, attempts);
                    throw CouponException.concurrentConflict(
                            "Coupon redemption could not be completed due to high concurrency; retry");
                }
                log.debug("Optimistic-lock retry {} for coupon {}", attempts, couponId);
            }
        }
    }

    // ===================== Cancellation decrement (Requirement 21.5, Property 21) =====================

    /**
     * Decrements both usage counters when a coupon-bearing booking is cancelled before payment
     * capture, restoring the coupon availability (Requirement 21.5, Property 21). Retries on
     * optimistic-lock contention.
     */
    public Coupon cancelRedemption(String code, UUID userId) {
        Coupon coupon = getCouponByCode(code);
        UUID couponId = coupon.getId();

        int attempts = 0;
        int maxAttempts = Math.max(1, props.getMaxRedemptionRetries());
        while (true) {
            attempts++;
            try {
                counterTransaction.applyCancellation(couponId, userId);
                Coupon updated = require(couponId);
                log.info("Cancelled redemption of coupon {} code {} (attempt {}); totalUsed now {}",
                        couponId, updated.getCode(), attempts, updated.getTotalUsed());
                return updated;
            } catch (OptimisticLockingFailureException e) {
                if (attempts >= maxAttempts) {
                    log.warn("Cancellation for coupon {} abandoned after {} contended attempts",
                            couponId, attempts);
                    throw CouponException.concurrentConflict(
                            "Coupon cancellation could not be completed due to high concurrency; retry");
                }
            }
        }
    }

    // ===================== Reads / helpers =====================

    @Transactional(readOnly = true)
    public int currentUserUsage(UUID couponId, UUID userId) {
        return usageRepository.findByCouponIdAndUserId(couponId, userId)
                .map(CouponUsage::getUsageCount)
                .orElse(0);
    }

    private Coupon require(UUID couponId) {
        return couponRepository.findById(couponId)
                .orElseThrow(() -> CouponException.notFound("Coupon " + couponId + " not found"));
    }

    /** Builds a descriptive, PII-free message for a violated constraint (Requirement 21.2). */
    private String describe(ConstraintCode code, Coupon coupon, BigDecimal orderValue) {
        return switch (code) {
            case COUPON_INACTIVE -> "Coupon '" + coupon.getCode() + "' is not active";
            case COUPON_NOT_STARTED -> "Coupon '" + coupon.getCode() + "' is not valid until "
                    + coupon.getValidFrom();
            case COUPON_EXPIRED -> "Coupon '" + coupon.getCode() + "' expired on "
                    + coupon.getExpiryDate();
            case MIN_ORDER_VALUE_NOT_MET -> "Order value " + orderValue
                    + " is below the minimum " + coupon.getMinOrderValue()
                    + " required for coupon '" + coupon.getCode() + "'";
            case PER_USER_LIMIT_REACHED -> "Per-user usage limit ("
                    + coupon.getPerUserLimit() + ") reached for coupon '" + coupon.getCode() + "'";
            case TOTAL_LIMIT_REACHED -> "Total usage limit (" + coupon.getTotalLimit()
                    + ") reached for coupon '" + coupon.getCode() + "'";
        };
    }
}
