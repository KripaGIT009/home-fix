package com.homefix.promotion.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.homefix.promotion.config.PromotionProperties;
import com.homefix.promotion.domain.Coupon;
import com.homefix.promotion.domain.DiscountType;
import com.homefix.promotion.service.CouponException.ConstraintCode;
import com.homefix.promotion.support.InMemoryCouponRepository;
import com.homefix.promotion.support.InMemoryCouponUsageRepository;

/**
 * Unit tests for {@link CouponService} (Requirement 21).
 *
 * <p>Covers: coupon creation attribute validation and duplicate rejection (21.1), checkout
 * constraint-violation messages and codes (21.2), atomic redemption that never exceeds the total or
 * per-user limits including under concurrency (21.3, Property 20), immediate deactivation blocking
 * further redemptions (21.4), and cancellation decrement restoring the counters (21.5, Property 21).
 *
 * <p>Example-based and concurrency-simulated; no Spring context, DB, or Kafka. The real
 * {@link CouponCounterTransaction} is used against in-memory repositories so the paired-counter
 * mutation logic is exercised directly. To model the production {@code SERIALIZABLE} transaction
 * boundary (which the transactional proxy provides at runtime), each counter mutation is invoked
 * under a shared lock in the concurrency test.
 */
class CouponServiceTest {

    private static final Instant NOW = Instant.parse("2024-06-01T12:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2024, 6, 1);
    private static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_USER = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private final InMemoryCouponRepository coupons = new InMemoryCouponRepository();
    private final InMemoryCouponUsageRepository usages = new InMemoryCouponUsageRepository();
    private final PromotionProperties props = new PromotionProperties();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private CouponService service() {
        CouponCounterTransaction counter = new CouponCounterTransaction(coupons, usages);
        return new CouponService(coupons, usages, counter, props, clock);
    }

    private CreateCouponCommand flatCommand(String code) {
        return new CreateCouponCommand(code, DiscountType.FLAT, new BigDecimal("50"),
                new BigDecimal("100"), null, TODAY.minusDays(1), TODAY.plusDays(30), 2, 3);
    }

    // ===================== Creation (Requirement 21.1) =====================

    @Test
    void createsCouponWithValidAttributes() {
        CouponService service = service();

        Coupon created = service.createCoupon(flatCommand("SAVE50"));

        assertThat(created.getCode()).isEqualTo("SAVE50");
        assertThat(created.getDiscountType()).isEqualTo(DiscountType.FLAT);
        assertThat(created.isActive()).isTrue();
        assertThat(created.getTotalUsed()).isZero();
        assertThat(coupons.findByCode("save50")).isPresent(); // case-insensitive lookup
    }

    @Test
    void rejectsInvalidCode() {
        CouponService service = service();

        assertThatThrownBy(() -> service.createCoupon(flatCommand("no")))
                .isInstanceOf(CouponException.class)
                .satisfies(e -> {
                    CouponException ce = (CouponException) e;
                    assertThat(ce.getErrorCode()).isEqualTo("VALIDATION_ERROR");
                    assertThat(ce.getMessage()).contains("4-20 alphanumeric");
                });
    }

    @Test
    void rejectsPercentageCouponWithoutCap() {
        CouponService service = service();
        CreateCouponCommand cmd = new CreateCouponCommand("PCT10", DiscountType.PERCENTAGE,
                new BigDecimal("10"), new BigDecimal("100"), null,
                TODAY.minusDays(1), TODAY.plusDays(10), 1, 5);

        assertThatThrownBy(() -> service.createCoupon(cmd))
                .isInstanceOf(CouponException.class)
                .hasMessageContaining("maximum discount cap");
    }

    @Test
    void rejectsExpiryNotAfterValidFrom() {
        CouponService service = service();
        CreateCouponCommand cmd = new CreateCouponCommand("WINDOW", DiscountType.FLAT,
                new BigDecimal("5"), BigDecimal.ZERO, null, TODAY, TODAY, 1, 1);

        assertThatThrownBy(() -> service.createCoupon(cmd))
                .isInstanceOf(CouponException.class)
                .hasMessageContaining("Expiry date must be after");
    }

    @Test
    void rejectsDuplicateCodeCaseInsensitively() {
        CouponService service = service();
        service.createCoupon(flatCommand("DUPE10"));

        assertThatThrownBy(() -> service.createCoupon(flatCommand("dupe10")))
                .isInstanceOf(CouponException.class)
                .satisfies(e -> assertThat(((CouponException) e).getErrorCode())
                        .isEqualTo("DUPLICATE_COUPON_CODE"));
    }

    // ===================== Validation constraint messages (Requirement 21.2) =====================

    @Test
    void validationReturnsDiscountWhenApplicable() {
        CouponService service = service();
        service.createCoupon(flatCommand("SAVE50"));

        CouponValidationResult result = service.validate("save50", USER, new BigDecimal("200"));

        assertThat(result.code()).isEqualTo("SAVE50");
        assertThat(result.discountAmount()).isEqualByComparingTo("50.00");
    }

    @Test
    void validationRejectsBelowMinimumOrderValueWithSpecificCode() {
        CouponService service = service();
        service.createCoupon(flatCommand("SAVE50")); // min order value 100

        assertThatThrownBy(() -> service.validate("SAVE50", USER, new BigDecimal("40")))
                .isInstanceOf(CouponException.class)
                .satisfies(e -> {
                    CouponException ce = (CouponException) e;
                    assertThat(ce.getErrorCode())
                            .isEqualTo(ConstraintCode.MIN_ORDER_VALUE_NOT_MET.name());
                    assertThat(ce.getMessage()).contains("below the minimum");
                });
    }

    @Test
    void validationRejectsInactiveCoupon() {
        CouponService service = service();
        Coupon coupon = service.createCoupon(flatCommand("SAVE50"));
        service.deactivateCoupon(coupon.getId());

        assertThatThrownBy(() -> service.validate("SAVE50", USER, new BigDecimal("200")))
                .isInstanceOf(CouponException.class)
                .satisfies(e -> assertThat(((CouponException) e).getErrorCode())
                        .isEqualTo(ConstraintCode.COUPON_INACTIVE.name()));
    }

    // ===================== Atomic redemption (Requirement 21.3, Property 20) =====================

    @Test
    void redemptionIncrementsBothCounters() {
        CouponService service = service();
        Coupon coupon = service.createCoupon(flatCommand("SAVE50"));

        Coupon after = service.redeem("SAVE50", USER);

        assertThat(after.getTotalUsed()).isEqualTo(1);
        assertThat(service.currentUserUsage(coupon.getId(), USER)).isEqualTo(1);
    }

    @Test
    void redemptionStopsAtPerUserLimit() {
        CouponService service = service();
        service.createCoupon(flatCommand("SAVE50")); // per-user limit 2, total 3

        service.redeem("SAVE50", USER);
        service.redeem("SAVE50", USER);

        assertThatThrownBy(() -> service.redeem("SAVE50", USER))
                .isInstanceOf(CouponException.class)
                .satisfies(e -> assertThat(((CouponException) e).getErrorCode())
                        .isEqualTo(ConstraintCode.PER_USER_LIMIT_REACHED.name()));
    }

    @Test
    void redemptionStopsAtTotalLimit() {
        CouponService service = service();
        // per-user 5, total 2 so the total limit is hit first across two users.
        CreateCouponCommand cmd = new CreateCouponCommand("TOTAL2", DiscountType.FLAT,
                new BigDecimal("5"), BigDecimal.ZERO, null, TODAY.minusDays(1), TODAY.plusDays(5), 5, 2);
        service.createCoupon(cmd);

        service.redeem("TOTAL2", USER);
        service.redeem("TOTAL2", OTHER_USER);

        assertThatThrownBy(() -> service.redeem("TOTAL2",
                UUID.fromString("33333333-3333-3333-3333-333333333333")))
                .isInstanceOf(CouponException.class)
                .satisfies(e -> assertThat(((CouponException) e).getErrorCode())
                        .isEqualTo(ConstraintCode.TOTAL_LIMIT_REACHED.name()));
    }

    /**
     * Property 20: under many concurrent redemptions, committed counters never exceed the total or
     * per-user limits (Requirement 21.3). Each counter mutation is applied under a shared lock to
     * model the production SERIALIZABLE transaction boundary.
     */
    @Test
    void concurrentRedemptionsNeverExceedLimits() throws Exception {
        // total limit 10, per-user limit 3 — deliberately smaller than the number of attempts.
        int totalLimit = 10;
        int perUserLimit = 3;
        CreateCouponCommand cmd = new CreateCouponCommand("RUSH", DiscountType.FLAT,
                new BigDecimal("5"), BigDecimal.ZERO, null, TODAY.minusDays(1), TODAY.plusDays(5),
                perUserLimit, totalLimit);

        Object lock = new Object();
        CouponCounterTransaction counter = new CouponCounterTransaction(coupons, usages) {
            @Override
            public void applyRedemption(UUID couponId, UUID userId) {
                synchronized (lock) {
                    super.applyRedemption(couponId, userId);
                }
            }
        };
        CouponService service = new CouponService(coupons, usages, counter, props, clock);
        Coupon coupon = service.createCoupon(cmd);

        int threads = 8;
        int attemptsPerThread = 10;
        UUID[] users = {USER, OTHER_USER,
                UUID.fromString("33333333-3333-3333-3333-333333333333"),
                UUID.fromString("44444444-4444-4444-4444-444444444444")};

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        ConcurrentLinkedQueue<Throwable> unexpected = new ConcurrentLinkedQueue<>();

        for (int t = 0; t < threads; t++) {
            final UUID user = users[t % users.length];
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < attemptsPerThread; i++) {
                        try {
                            service.redeem("RUSH", user);
                            successes.incrementAndGet();
                        } catch (CouponException expected) {
                            // Hitting a limit or transient contention is a valid outcome.
                            String code = expected.getErrorCode();
                            if (!code.equals(ConstraintCode.TOTAL_LIMIT_REACHED.name())
                                    && !code.equals(ConstraintCode.PER_USER_LIMIT_REACHED.name())
                                    && !code.equals("CONCURRENT_MODIFICATION")) {
                                unexpected.add(expected);
                            }
                        }
                    }
                } catch (Throwable th) {
                    unexpected.add(th);
                }
            });
        }

        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        assertThat(unexpected).isEmpty();

        Coupon reloaded = service.getCoupon(coupon.getId());
        // Never over-redeemed past the total limit (Property 20).
        assertThat(reloaded.getTotalUsed()).isLessThanOrEqualTo(totalLimit);
        // Successful redemptions equal the committed total exactly (counters moved together).
        assertThat(successes.get()).isEqualTo(reloaded.getTotalUsed());
        // No user exceeds the per-user limit.
        for (UUID user : users) {
            assertThat(service.currentUserUsage(coupon.getId(), user))
                    .isLessThanOrEqualTo(perUserLimit);
        }
    }

    // ===================== Deactivation blocks redemption (Requirement 21.4) =====================

    @Test
    void deactivationBlocksFurtherRedemptions() {
        CouponService service = service();
        Coupon coupon = service.createCoupon(flatCommand("SAVE50"));
        service.redeem("SAVE50", USER); // one redemption before deactivation succeeds

        service.deactivateCoupon(coupon.getId());

        assertThatThrownBy(() -> service.redeem("SAVE50", USER))
                .isInstanceOf(CouponException.class)
                .satisfies(e -> assertThat(((CouponException) e).getErrorCode())
                        .isEqualTo(ConstraintCode.COUPON_INACTIVE.name()));
        assertThat(service.getCoupon(coupon.getId()).getTotalUsed()).isEqualTo(1);
    }

    // ===================== Cancellation decrement (Requirement 21.5, Property 21) =====================

    @Test
    void cancellationRestoresCountersAtomically() {
        CouponService service = service();
        Coupon coupon = service.createCoupon(flatCommand("SAVE50"));
        service.redeem("SAVE50", USER);
        service.redeem("SAVE50", USER);
        assertThat(service.getCoupon(coupon.getId()).getTotalUsed()).isEqualTo(2);

        service.cancelRedemption("SAVE50", USER);

        Coupon after = service.getCoupon(coupon.getId());
        assertThat(after.getTotalUsed()).isEqualTo(1);
        assertThat(service.currentUserUsage(coupon.getId(), USER)).isEqualTo(1);
    }

    @Test
    void redeemThenCancelReturnsCountersToStartingValues() {
        CouponService service = service();
        Coupon coupon = service.createCoupon(flatCommand("SAVE50"));

        service.redeem("SAVE50", USER);
        service.cancelRedemption("SAVE50", USER);

        Coupon after = service.getCoupon(coupon.getId());
        assertThat(after.getTotalUsed()).isZero();
        assertThat(service.currentUserUsage(coupon.getId(), USER)).isZero();
    }
}
