package com.homefix.pricing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static com.homefix.pricing.support.TestData.SUBCATEGORY;
import static com.homefix.pricing.support.TestData.baseParams;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.homefix.pricing.cache.InMemoryPricingConfigCacheAdapter;
import com.homefix.pricing.config.PricingParametersPort;
import com.homefix.pricing.config.PricingProperties;
import com.homefix.pricing.coupon.CouponDiscountPort;
import com.homefix.pricing.domain.PriceBreakdown;
import com.homefix.pricing.domain.PricingParameters;

/**
 * Orchestration tests for {@link PriceQuoteService}: read-through of Admin parameters,
 * coupon application, and the guarantee that a coupon cannot drop the total below the floor
 * (Requirement 6.1, 6.10, 6.11).
 *
 * <p>Coupon discounts come from the Promotion Service through {@link CouponDiscountPort}; a
 * map-backed fake stands in for it and records what it was asked, so the tests can pin the order
 * value the discount is computed on. The HTTP adapter itself is covered by
 * {@code PromotionCouponDiscountAdapterTest}.
 */
class PriceQuoteServiceTest {

    private final PricingProperties props = new PricingProperties();

    /** Simple fake source-of-truth for parameters. */
    private static final class FakeParametersPort implements PricingParametersPort {
        private final Map<UUID, PricingParameters> store = new ConcurrentHashMap<>();
        @Override public Optional<PricingParameters> findBySubcategoryId(UUID id) {
            return Optional.ofNullable(store.get(id));
        }
        @Override public PricingParameters save(PricingParameters p) {
            store.put(p.subcategoryId(), p);
            return p;
        }
        @Override public List<PricingParameters> findAll(int limit) {
            return store.values().stream().limit(limit).toList();
        }
    }

    private FakeParametersPort parametersPort;
    private InMemoryPricingConfigCacheAdapter cache;
    private PricingConfigService configService;
    /** Flat discounts by code; an unknown code is rejected like the Promotion Service does. */
    private Map<String, BigDecimal> coupons;
    private List<BigDecimal> quotedOrderValues;
    private List<UUID> quotedUsers;
    private PriceQuoteService quoteService;

    @BeforeEach
    void setUp() {
        parametersPort = new FakeParametersPort();
        cache = new InMemoryPricingConfigCacheAdapter(props, Clock.systemUTC());
        configService = new PricingConfigService(cache, parametersPort);
        coupons = new ConcurrentHashMap<>();
        quotedOrderValues = new ArrayList<>();
        quotedUsers = new ArrayList<>();
        CouponDiscountPort couponDiscounts = (code, userId, orderValue) -> {
            quotedOrderValues.add(orderValue);
            quotedUsers.add(userId);
            BigDecimal discount = coupons.get(code);
            if (discount == null) {
                throw PricingException.coupon("COUPON_NOT_FOUND", "Coupon '" + code + "' does not exist");
            }
            return discount.min(orderValue);
        };
        quoteService = new PriceQuoteService(new PricingService(props), configService,
                couponDiscounts, props);
    }

    private PriceRequest plain() {
        return new PriceRequest(SUBCATEGORY, false, false, null, null, null, null, null, null, null);
    }

    @Test
    void quoteUsesConfiguredParameters() {
        parametersPort.save(baseParams("100.00"));

        PriceBreakdown b = quoteService.quote(plain());

        assertThat(b.basePrice()).isEqualByComparingTo("100.00");
        assertThat(b.total()).isEqualByComparingTo("100.00");
        assertThat(b.componentSum()).isEqualByComparingTo(b.total());
    }

    @Test
    void missingParameters_isRejected() {
        assertThatThrownBy(() -> quoteService.quote(plain()))
                .isInstanceOf(PricingException.class)
                .satisfies(ex -> assertThat(((PricingException) ex).getErrorCode())
                        .isEqualTo("PRICING_PARAMETERS_NOT_FOUND"));
    }

    @Test
    void adminUpdateIsReflectedOnNextQuote_afterInvalidation() {
        parametersPort.save(baseParams("100.00"));
        assertThat(quoteService.quote(plain()).basePrice()).isEqualByComparingTo("100.00");

        // Admin updates the base price through the config service (invalidates the cache).
        configService.updateParameters(baseParams("175.00"));

        assertThat(quoteService.quote(plain()).basePrice()).isEqualByComparingTo("175.00");
    }

    @Test
    void couponApplied_reducesTotalAndKeepsComponentsSummed() {
        parametersPort.save(baseParams("100.00"));
        coupons.put("SAVE20", new BigDecimal("20.00"));
        UUID user = UUID.randomUUID();

        PriceBreakdown b = quoteService.quote(new PriceRequest(SUBCATEGORY, false, false, null,
                null, null, null, "SAVE20", user, null));

        assertThat(b.couponAmount()).isEqualByComparingTo("20.00");
        assertThat(b.total()).isEqualByComparingTo("80.00");
        assertThat(b.componentSum()).isEqualByComparingTo(b.total());
        // The discount is quoted on the pre-coupon total, for the requesting user.
        assertThat(quotedOrderValues).singleElement()
                .satisfies(v -> assertThat(v).isEqualByComparingTo("100.00"));
        assertThat(quotedUsers).containsExactly(user);
    }

    @Test
    void couponCodeIsStrippedBeforeItIsQuoted() {
        parametersPort.save(baseParams("100.00"));
        coupons.put("SAVE20", new BigDecimal("20.00"));

        PriceBreakdown b = quoteService.quote(new PriceRequest(SUBCATEGORY, false, false, null,
                null, null, null, "  SAVE20 ", null, null));

        assertThat(b.couponAmount()).isEqualByComparingTo("20.00");
    }

    @Test
    void blankCouponCode_neverConsultsThePromotionService() {
        parametersPort.save(baseParams("100.00"));

        PriceBreakdown b = quoteService.quote(new PriceRequest(SUBCATEGORY, false, false, null,
                null, null, null, "   ", UUID.randomUUID(), null));

        assertThat(b.couponAmount()).isEqualByComparingTo("0.00");
        assertThat(quotedOrderValues).isEmpty();
    }

    @Test
    void discountAboveTheOrderValue_isClampedToIt() {
        parametersPort.save(baseParams("100.00"));
        // A dependency that ignores its contract must not push the pre-floor total negative.
        quoteService = new PriceQuoteService(new PricingService(props), configService,
                (code, userId, orderValue) -> new BigDecimal("250.00"), props);

        PriceBreakdown b = quoteService.quote(new PriceRequest(SUBCATEGORY, false, false, null,
                null, null, null, "WILD", null, null));

        assertThat(b.couponAmount()).isEqualByComparingTo("100.00");
        assertThat(b.total()).isGreaterThanOrEqualTo(new BigDecimal("0.01"));
        assertThat(b.componentSum()).isEqualByComparingTo(b.total());
    }

    @Test
    void promotionServiceUnavailable_failsTheQuoteWith503() {
        parametersPort.save(baseParams("100.00"));
        quoteService = new PriceQuoteService(new PricingService(props), configService,
                (code, userId, orderValue) -> {
                    throw PricingException.couponServiceUnavailable("down");
                }, props);

        assertThatThrownBy(() -> quoteService.quote(new PriceRequest(SUBCATEGORY, false, false,
                null, null, null, null, "SAVE20", UUID.randomUUID(), null)))
                .isInstanceOf(PricingException.class)
                .satisfies(ex -> {
                    assertThat(((PricingException) ex).getErrorCode())
                            .isEqualTo("COUPON_SERVICE_UNAVAILABLE");
                    assertThat(((PricingException) ex).getStatus().value()).isEqualTo(503);
                });
    }

    @Test
    void hugeCouponCannotDropTotalBelowFloor() {
        parametersPort.save(baseParams("100.00"));
        coupons.put("BIG", new BigDecimal("100.00"));

        PriceBreakdown b = quoteService.quote(new PriceRequest(SUBCATEGORY, false, false, null,
                null, null, null, "BIG", UUID.randomUUID(), null));

        assertThat(b.total()).isGreaterThanOrEqualTo(new BigDecimal("0.01"));
        assertThat(b.componentSum()).isEqualByComparingTo(b.total());
    }

    @Test
    void unknownCoupon_isRejected() {
        parametersPort.save(baseParams("100.00"));

        assertThatThrownBy(() -> quoteService.quote(new PriceRequest(SUBCATEGORY, false, false,
                null, null, null, null, "NOPE", UUID.randomUUID(), null)))
                .isInstanceOf(PricingException.class)
                .satisfies(ex -> assertThat(((PricingException) ex).getErrorCode())
                        .isEqualTo("COUPON_NOT_FOUND"));
    }
}
