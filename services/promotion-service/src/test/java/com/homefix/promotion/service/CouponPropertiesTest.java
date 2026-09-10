package com.homefix.promotion.service;

import static org.assertj.core.api.Assertions.assertThat;

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

import com.homefix.promotion.config.PromotionProperties;
import com.homefix.promotion.domain.Coupon;
import com.homefix.promotion.domain.DiscountType;
import com.homefix.promotion.service.CouponException.ConstraintCode;
import com.homefix.promotion.support.InMemoryCouponRepository;
import com.homefix.promotion.support.InMemoryCouponUsageRepository;

import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;

/**
 * Property-based tests for the Promotion/Coupon Service correctness properties 20–21 (design.md
 * "Correctness Properties", Requirement 21). Each property runs a minimum of 100 tries and is
 * tagged with the required {@code Feature: homefix-platform, Property N} label.
 *
 * <p>These complement the example-based {@link CouponServiceTest} by asserting the counter
 * invariants hold universally across generated limits, user populations, and concurrent request
 * volumes rather than for hand-picked examples.
 *
 * <p>As in {@link CouponServiceTest}, the real {@link CouponCounterTransaction} runs against
 * in-memory repositories, and each counter mutation is applied under a shared lock to model the
 * production {@code SERIALIZABLE} transaction boundary that the transactional proxy provides at
 * runtime.
 */
class CouponPropertiesTest {

    private static final Instant NOW = Instant.parse("2024-06-01T12:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2024, 6, 1);

    // ============================================================================================
    // Property 20: Coupon usage counter atomicity
    // ============================================================================================

    @Property(tries = 100)
    @Label("Feature: homefix-platform, Property 20: Coupon usage counter atomicity")
    void concurrentRedemptionsNeverExceedConfiguredLimits(
            @ForAll @IntRange(min = 1, max = 20) int totalLimit,
            @ForAll @IntRange(min = 1, max = 5) int perUserLimit,
            @ForAll @IntRange(min = 2, max = 8) int threads,
            @ForAll @IntRange(min = 1, max = 6) int userCount,
            @ForAll @IntRange(min = 1, max = 6) int attemptsPerThread) throws Exception {

        InMemoryCouponRepository coupons = new InMemoryCouponRepository();
        InMemoryCouponUsageRepository usages = new InMemoryCouponUsageRepository();
        PromotionProperties props = new PromotionProperties();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

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

        Coupon coupon = service.createCoupon(new CreateCouponCommand("RUSH", DiscountType.FLAT,
                new BigDecimal("5"), BigDecimal.ZERO, null, TODAY.minusDays(1), TODAY.plusDays(5),
                perUserLimit, totalLimit));

        UUID[] users = new UUID[userCount];
        for (int i = 0; i < userCount; i++) {
            users[i] = new UUID(0L, i + 1);
        }

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
                            String code = expected.getErrorCode();
                            // Hitting a limit or transient contention is a valid outcome.
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
        // The total usage counter never exceeds the configured total limit (Property 20).
        assertThat(reloaded.getTotalUsed()).isLessThanOrEqualTo(totalLimit);
        // Counters move together: successful redemptions equal the committed total exactly.
        assertThat(successes.get()).isEqualTo(reloaded.getTotalUsed());
        // No per-user counter exceeds the configured per-user limit (Property 20).
        for (UUID user : users) {
            assertThat(service.currentUserUsage(coupon.getId(), user))
                    .isLessThanOrEqualTo(perUserLimit);
        }
    }

    // ============================================================================================
    // Property 21: Coupon cancellation counter decrement
    // ============================================================================================

    @Property(tries = 100)
    @Label("Feature: homefix-platform, Property 21: Coupon cancellation counter decrement")
    void cancellationRestoresBothCountersToPreRedemptionValues(
            @ForAll @IntRange(min = 1, max = 10) int redemptions,
            @ForAll @IntRange(min = 1, max = 10) int extraPreRedemptions) {

        InMemoryCouponRepository coupons = new InMemoryCouponRepository();
        InMemoryCouponUsageRepository usages = new InMemoryCouponUsageRepository();
        PromotionProperties props = new PromotionProperties();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

        CouponCounterTransaction counter = new CouponCounterTransaction(coupons, usages);
        CouponService service = new CouponService(coupons, usages, counter, props, clock);

        UUID user = new UUID(0L, 1L);
        UUID otherUser = new UUID(0L, 2L);

        // Per-user and total limits are large enough that every generated redemption succeeds.
        int perUserLimit = redemptions + extraPreRedemptions + 5;
        int totalLimit = perUserLimit * 3 + 5;
        Coupon coupon = service.createCoupon(new CreateCouponCommand("CANCELME", DiscountType.FLAT,
                new BigDecimal("5"), BigDecimal.ZERO, null, TODAY.minusDays(1), TODAY.plusDays(5),
                perUserLimit, totalLimit));
        UUID couponId = coupon.getId();

        // Establish a pre-redemption baseline: some usage by our user plus some by another user.
        for (int i = 0; i < extraPreRedemptions; i++) {
            service.redeem("CANCELME", user);
            service.redeem("CANCELME", otherUser);
        }
        int baselineTotal = service.getCoupon(couponId).getTotalUsed();
        int baselineUserUsage = service.currentUserUsage(couponId, user);

        // Now redeem `redemptions` times for `user`, then cancel each one.
        for (int i = 0; i < redemptions; i++) {
            service.redeem("CANCELME", user);
        }
        assertThat(service.getCoupon(couponId).getTotalUsed())
                .isEqualTo(baselineTotal + redemptions);
        assertThat(service.currentUserUsage(couponId, user))
                .isEqualTo(baselineUserUsage + redemptions);

        for (int i = 0; i < redemptions; i++) {
            service.cancelRedemption("CANCELME", user);
        }

        // Both counters are atomically restored to their exact pre-redemption values (Property 21).
        assertThat(service.getCoupon(couponId).getTotalUsed()).isEqualTo(baselineTotal);
        assertThat(service.currentUserUsage(couponId, user)).isEqualTo(baselineUserUsage);
        // The unrelated user's counter is untouched by the cancellations.
        assertThat(service.currentUserUsage(couponId, otherUser)).isEqualTo(extraPreRedemptions);
    }
}
