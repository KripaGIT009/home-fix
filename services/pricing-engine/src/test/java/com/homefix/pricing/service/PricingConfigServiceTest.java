package com.homefix.pricing.service;

import static com.homefix.pricing.support.TestData.SUBCATEGORY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.homefix.pricing.cache.InMemoryPricingConfigCacheAdapter;
import com.homefix.pricing.config.InMemoryPricingParametersAdapter;
import com.homefix.pricing.config.PricingProperties;
import com.homefix.pricing.domain.PricingParameters;

/**
 * Unit tests for the Admin Portal paths of {@link PricingConfigService} (Requirement 6.11): the
 * listing and the merge update, whose whole point is that the fields the portal never sends
 * ({@code taxRate}, override floor/ceiling) survive an edit, and that the cache is invalidated the
 * same way as for the replace-style parameters PUT.
 */
class PricingConfigServiceTest {

    private InMemoryPricingParametersAdapter store;
    private InMemoryPricingConfigCacheAdapter cache;
    private PricingConfigService service;

    @BeforeEach
    void setUp() {
        store = new InMemoryPricingParametersAdapter();
        cache = new InMemoryPricingConfigCacheAdapter(new PricingProperties(), Clock.systemUTC());
        service = new PricingConfigService(cache, store);
    }

    private static PricingParameters stored() {
        return new PricingParameters(SUBCATEGORY, new BigDecimal("100.00"), new BigDecimal("2.00"),
                new BigDecimal("50.00"), new BigDecimal("20.00"), new BigDecimal("30.00"),
                new BigDecimal("0.10"), new BigDecimal("0.18"), new BigDecimal("2.0"),
                new BigDecimal("1.5"), new BigDecimal("10.00"), new BigDecimal("500.00"));
    }

    private static PricingParameters portalChanges(String basePrice, String feeRate) {
        return new PricingParameters(SUBCATEGORY, basePrice == null ? null : new BigDecimal(basePrice),
                new BigDecimal("3.00"), null, null, null,
                feeRate == null ? null : new BigDecimal(feeRate), null, null, null, null, null);
    }

    @Test
    void mergeKeepsEveryFieldThePortalDidNotSend() {
        store.save(stored());

        PricingParameters merged = service.mergeParameters(portalChanges("120.00", "0.125"));

        assertThat(merged.basePrice()).isEqualByComparingTo("120.00");
        assertThat(merged.perKmRate()).isEqualByComparingTo("3.00");
        assertThat(merged.platformFeeRate()).isEqualByComparingTo("0.125");
        // Left out of the change -> kept.
        assertThat(merged.maxTravelCharge()).isEqualByComparingTo("50.00");
        assertThat(merged.nightSurcharge()).isEqualByComparingTo("20.00");
        assertThat(merged.emergencyMultiplier()).isEqualByComparingTo("2.0");
        // Never managed by the portal -> kept.
        assertThat(merged.taxRate()).isEqualByComparingTo("0.18");
        assertThat(merged.overrideFloor()).isEqualByComparingTo("10.00");
        assertThat(merged.overrideCeiling()).isEqualByComparingTo("500.00");
        assertThat(store.findBySubcategoryId(SUBCATEGORY)).contains(merged);
    }

    @Test
    void mergeInvalidatesTheCacheSoTheNextQuoteSeesTheEdit() {
        store.save(stored());
        // Warm the cache with the old value, as a quote would.
        assertThat(service.requireParameters(SUBCATEGORY).basePrice()).isEqualByComparingTo("100.00");

        service.mergeParameters(portalChanges("120.00", null));

        assertThat(cache.get(SUBCATEGORY)).isEmpty();
        assertThat(service.requireParameters(SUBCATEGORY).basePrice()).isEqualByComparingTo("120.00");
    }

    @Test
    void mergeOntoAStaleCacheUsesTheSourceOfTruth() {
        store.save(stored());
        service.requireParameters(SUBCATEGORY);                 // cache holds base 100
        store.save(new PricingParameters(SUBCATEGORY, new BigDecimal("100.00"), null, null, null,
                null, null, new BigDecimal("0.05"), null, null, null, null)); // taxRate changed

        PricingParameters merged = service.mergeParameters(portalChanges("130.00", null));

        assertThat(merged.taxRate()).isEqualByComparingTo("0.05");
    }

    @Test
    void mergeCreatesAnUnconfiguredSubcategoryLikeTheParametersPut() {
        PricingParameters created = service.mergeParameters(portalChanges("99.00", "0.10"));

        assertThat(created.basePrice()).isEqualByComparingTo("99.00");
        assertThat(created.taxRate()).isNull();
        assertThat(store.findBySubcategoryId(SUBCATEGORY)).isPresent();
    }

    @Test
    void mergeRequiresABasePriceWhenCreating() {
        assertThatThrownBy(() -> service.mergeParameters(portalChanges(null, "0.10")))
                .isInstanceOf(PricingException.class)
                .extracting("errorCode").isEqualTo("VALIDATION_ERROR");
        assertThat(store.findBySubcategoryId(SUBCATEGORY)).isEmpty();
    }

    /**
     * Out-of-range values are refused with 400 and every violation listed, rather than stored for
     * the engine to clamp silently at quote time.
     */
    @Test
    void outOfRangeParametersAreRejectedAndNothingIsStored() {
        PricingParameters bad = new PricingParameters(SUBCATEGORY, new BigDecimal("-1.00"),
                new BigDecimal("-2.00"), null, new BigDecimal("-5.00"), null,
                new BigDecimal("15"), new BigDecimal("1.5"), new BigDecimal("0.5"),
                new BigDecimal("150"), new BigDecimal("600.00"), new BigDecimal("500.00"));

        assertThatThrownBy(() -> service.updateParameters(bad))
                .isInstanceOfSatisfying(PricingException.class, e -> {
                    assertThat(e.getStatus().value()).isEqualTo(400);
                    assertThat(e.getErrorCode()).isEqualTo("VALIDATION_ERROR");
                    assertThat(e.getDetails()).containsExactlyInAnyOrder(
                            "basePrice must not be negative",
                            "perKmRate must not be negative",
                            "nightSurcharge must not be negative",
                            "platformFeeRate must be between 0 and 1",
                            "taxRate must be between 0 and 1",
                            "emergencyMultiplier must be between 1 and 10",
                            "surgeMultiplier must be between 1 and 10",
                            "overrideFloor must not be greater than overrideCeiling");
                });
        assertThat(store.findBySubcategoryId(SUBCATEGORY)).isEmpty();
    }

    @Test
    void portalEditIsRangeCheckedAfterTheMerge() {
        store.save(stored());

        // 150% platform fee from the portal arrives as the fraction 1.5.
        assertThatThrownBy(() -> service.mergeParameters(portalChanges("120.00", "1.5")))
                .isInstanceOf(PricingException.class)
                .extracting("errorCode").isEqualTo("VALIDATION_ERROR");
        assertThat(store.findBySubcategoryId(SUBCATEGORY)).contains(stored());
    }

    @Test
    void boundaryValuesAreAccepted() {
        PricingParameters edge = new PricingParameters(SUBCATEGORY, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ZERO,
                BigDecimal.ONE, BigDecimal.TEN, new BigDecimal("10.00"), new BigDecimal("10.00"));

        assertThat(service.updateParameters(edge)).isEqualTo(edge);
    }

    @Test
    void listReturnsEveryConfiguredSubcategory() {
        store.save(stored());
        UUID other = UUID.fromString("99999999-9999-9999-9999-999999999999");
        store.save(new PricingParameters(other, new BigDecimal("50.00"), null, null, null, null,
                null, null, null, null, null, null));

        assertThat(service.listParameters())
                .extracting(PricingParameters::subcategoryId)
                .containsExactlyInAnyOrder(SUBCATEGORY, other);
    }
}
