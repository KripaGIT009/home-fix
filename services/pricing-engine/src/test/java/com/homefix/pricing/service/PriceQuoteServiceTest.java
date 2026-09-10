package com.homefix.pricing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static com.homefix.pricing.support.TestData.SUBCATEGORY;
import static com.homefix.pricing.support.TestData.baseParams;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.homefix.pricing.cache.InMemoryPricingConfigCacheAdapter;
import com.homefix.pricing.config.PricingParametersPort;
import com.homefix.pricing.config.PricingProperties;
import com.homefix.pricing.coupon.CouponLookupPort;
import com.homefix.pricing.coupon.CouponUsagePort;
import com.homefix.pricing.domain.Coupon;
import com.homefix.pricing.domain.PriceBreakdown;
import com.homefix.pricing.domain.PricingParameters;

/**
 * Orchestration tests for {@link PriceQuoteService}: read-through of Admin parameters,
 * coupon application, and the guarantee that a coupon cannot drop the total below the floor
 * (Requirement 6.1, 6.10, 6.11).
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
    }

    private FakeParametersPort parametersPort;
    private InMemoryPricingConfigCacheAdapter cache;
    private PricingConfigService configService;
    private CouponUsagePort usagePort;
    private CouponLookupPort couponLookup;
    private Map<String, Coupon> coupons;
    private PriceQuoteService quoteService;

    @BeforeEach
    void setUp() {
        parametersPort = new FakeParametersPort();
        cache = new InMemoryPricingConfigCacheAdapter(props, Clock.systemUTC());
        configService = new PricingConfigService(cache, parametersPort);
        usagePort = new CouponUsagePort() {
            @Override public int totalRedemptions(String code) { return 0; }
            @Override public int userRedemptions(String code, UUID userId) { return 0; }
        };
        coupons = new ConcurrentHashMap<>();
        couponLookup = code -> Optional.ofNullable(coupons.get(code));
        PricingService pricingService = new PricingService(props);
        CouponService couponService = new CouponService(usagePort);
        quoteService = new PriceQuoteService(pricingService, configService, couponService,
                couponLookup, props);
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
        coupons.put("SAVE20", new Coupon("SAVE20", true, null, null, null,
                new BigDecimal("20.00"), null, null, null));

        PriceBreakdown b = quoteService.quote(new PriceRequest(SUBCATEGORY, false, false, null,
                null, null, null, "SAVE20", UUID.randomUUID(), null));

        assertThat(b.couponAmount()).isEqualByComparingTo("20.00");
        assertThat(b.total()).isEqualByComparingTo("80.00");
        assertThat(b.componentSum()).isEqualByComparingTo(b.total());
    }

    @Test
    void hugeCouponCannotDropTotalBelowFloor() {
        parametersPort.save(baseParams("100.00"));
        coupons.put("BIG", new Coupon("BIG", true, null, null, null,
                new BigDecimal("100.00"), null, null, null));

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
