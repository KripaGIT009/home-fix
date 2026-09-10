package com.homefix.pricing.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.UUID;

import com.homefix.pricing.config.PricingProperties;
import com.homefix.pricing.domain.PriceBreakdown;
import com.homefix.pricing.domain.PricingParameters;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.BigRange;

/**
 * Property-based tests for the Pricing Engine correctness properties 1–7 (design.md
 * "Correctness Properties", Requirement 6). Each property runs a minimum of 100 tries and is
 * tagged with the required {@code Feature: homefix-platform, Property N} label.
 *
 * <p>These complement the example-based {@link PricingServiceTest} and
 * {@link ProviderOverrideServiceTest} by asserting the properties hold universally across
 * generated inputs rather than for hand-picked examples.
 */
class PricingEnginePropertiesTest {

    private static final UUID SUBCATEGORY = UUID.fromString("11111111-1111-1111-1111-111111111111");
    /** Comparison precision for effective-factor ratios. */
    private static final int CALC_SCALE = 12;
    /** Multiplier clamp scale — must match {@code PricingService.CALC_SCALE}. */
    private static final int CLAMP_SCALE = 10;

    private final PricingProperties properties = new PricingProperties();
    private final PricingService service = new PricingService(properties);
    private final ProviderOverrideService overrideService = new ProviderOverrideService();

    private final BigDecimal emergencyCap = properties.getMaxEmergencyMultiplier();
    private final BigDecimal surgeCap = properties.getMaxSurgeMultiplier();
    private final BigDecimal minTotal = properties.getMinTotal();

    // ============================================================================================
    // Property 1: Pricing formula non-negativity
    // ============================================================================================

    @Property(tries = 100)
    @Label("Feature: homefix-platform, Property 1: Pricing formula non-negativity")
    void pricingTotalNeverBelowMinimum(
            @ForAll("params") PricingParameters params,
            @ForAll boolean emergency,
            @ForAll boolean surge,
            @ForAll("nonNegativeMoney") BigDecimal distanceKm,
            @ForAll("nonNegativeMoney") BigDecimal timeCharge,
            @ForAll("nonNegativeMoney") BigDecimal partsMaterials,
            @ForAll("localTimes") LocalDateTime scheduledLocalTime,
            @ForAll @BigRange(min = "0.00", max = "100000.00") BigDecimal discount) {

        PriceRequest request = new PriceRequest(SUBCATEGORY, emergency, surge, distanceKm,
                timeCharge, partsMaterials, scheduledLocalTime, null, null, discount);

        PriceBreakdown breakdown = service.calculate(request, params);

        assertThat(breakdown.total()).isGreaterThanOrEqualTo(minTotal);
    }

    // ============================================================================================
    // Property 2: Emergency multiplier cap (default 2.0x)
    // ============================================================================================

    @Property(tries = 100)
    @Label("Feature: homefix-platform, Property 2: Emergency multiplier cap")
    void emergencyMultiplierNeverExceedsCap(
            @ForAll("positiveBasePrice") BigDecimal basePrice,
            @ForAll @BigRange(min = "1.0", max = "6.0") BigDecimal configuredEmergency) {

        PricingParameters params = paramsFor(basePrice, configuredEmergency, BigDecimal.ONE);
        PriceRequest request = emergencyRequest();

        PriceBreakdown breakdown = service.calculate(request, params);

        // effective emergency factor = 1 + emergencyCharge / basePrice; must be <= cap.
        BigDecimal effectiveFactor = effectiveFactor(basePrice, breakdown.emergencyCharge());
        assertThat(effectiveFactor).isLessThanOrEqualTo(emergencyCap);
    }

    // ============================================================================================
    // Property 3: Surge multiplier cap (default 2.0x)
    // ============================================================================================

    @Property(tries = 100)
    @Label("Feature: homefix-platform, Property 3: Surge multiplier cap")
    void surgeMultiplierNeverExceedsCap(
            @ForAll("positiveBasePrice") BigDecimal basePrice,
            @ForAll @BigRange(min = "1.0", max = "6.0") BigDecimal configuredSurge) {

        PricingParameters params = paramsFor(basePrice, BigDecimal.ONE, configuredSurge);
        PriceRequest request = surgeRequest();

        PriceBreakdown breakdown = service.calculate(request, params);

        // With no emergency, the entire surcharge is the surge component.
        BigDecimal effectiveFactor = effectiveFactor(basePrice, breakdown.demandSurgeCharge());
        assertThat(effectiveFactor).isLessThanOrEqualTo(surgeCap);
    }

    // ============================================================================================
    // Property 4: Combined multiplier cap (emergency first, effective <= sum of caps)
    // ============================================================================================

    @Property(tries = 100)
    @Label("Feature: homefix-platform, Property 4: Combined multiplier cap")
    void combinedMultiplierNeverExceedsSumOfCapsAndEmergencyAppliedFirst(
            @ForAll("positiveBasePrice") BigDecimal basePrice,
            @ForAll @BigRange(min = "1.0", max = "6.0") BigDecimal configuredEmergency,
            @ForAll @BigRange(min = "1.0", max = "6.0") BigDecimal configuredSurge) {

        PricingParameters params = paramsFor(basePrice, configuredEmergency, configuredSurge);
        PriceRequest request = new PriceRequest(SUBCATEGORY, true, true, null, null, null, null,
                null, null, null);

        PriceBreakdown breakdown = service.calculate(request, params);

        BigDecimal totalSurcharge = breakdown.emergencyCharge().add(breakdown.demandSurgeCharge());
        BigDecimal effectiveMultiplier = effectiveFactor(basePrice, totalSurcharge);

        // combined effective multiplier <= sum of both configured caps.
        BigDecimal combinedCap = emergencyCap.add(surgeCap);
        assertThat(effectiveMultiplier).isLessThanOrEqualTo(combinedCap);

        // Emergency applied first: the emergency component reflects base * (clampedEmergency - 1),
        // bounded above by the total surcharge, and never negative. The surge component absorbs
        // the remainder. Compare at monetary scale (both sides rounded HALF_UP).
        BigDecimal clampedEmergency = clamp(configuredEmergency, BigDecimal.ONE, emergencyCap);
        BigDecimal emergencyOnly = basePrice.multiply(clampedEmergency.subtract(BigDecimal.ONE));
        BigDecimal expectedEmergencyCharge =
                emergencyOnly.compareTo(totalSurcharge) > 0 ? totalSurcharge : emergencyOnly;

        assertThat(breakdown.emergencyCharge()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
        assertThat(breakdown.demandSurgeCharge()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
        // Emergency charge equals the emergency-first decomposition to within one rounding unit.
        BigDecimal roundingUnit = BigDecimal.ONE.movePointLeft(properties.getMoneyScale());
        assertThat(breakdown.emergencyCharge().subtract(round(expectedEmergencyCharge)).abs())
                .isLessThanOrEqualTo(roundingUnit);
    }

    // ============================================================================================
    // Property 5: Distance charge cap
    // ============================================================================================

    @Property(tries = 100)
    @Label("Feature: homefix-platform, Property 5: Distance charge cap")
    void distanceChargeNeverExceedsMaxTravelCharge(
            @ForAll @BigRange(min = "0.00", max = "1000.00") BigDecimal distanceKm,
            @ForAll @BigRange(min = "0.00", max = "100.00") BigDecimal perKmRate,
            @ForAll @BigRange(min = "0.00", max = "500.00") BigDecimal maxTravelCharge) {

        PricingParameters params = new PricingParameters(SUBCATEGORY, new BigDecimal("100.00"),
                perKmRate, maxTravelCharge, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, emergencyCap, surgeCap, new BigDecimal("10.00"),
                new BigDecimal("500.00"));
        PriceRequest request = new PriceRequest(SUBCATEGORY, false, false, distanceKm, null, null,
                null, null, null, null);

        PriceBreakdown breakdown = service.calculate(request, params);

        assertThat(breakdown.distanceCharge()).isLessThanOrEqualTo(round(maxTravelCharge));
    }

    // ============================================================================================
    // Property 6: Itemized price breakdown completeness (all components non-null, sum == total)
    // ============================================================================================

    @Property(tries = 100)
    @Label("Feature: homefix-platform, Property 6: Itemized price breakdown completeness")
    void breakdownComponentsAreCompleteAndSumToTotal(
            @ForAll("params") PricingParameters params,
            @ForAll boolean emergency,
            @ForAll boolean surge,
            @ForAll("nonNegativeMoney") BigDecimal distanceKm,
            @ForAll("nonNegativeMoney") BigDecimal timeCharge,
            @ForAll("nonNegativeMoney") BigDecimal partsMaterials,
            @ForAll("localTimes") LocalDateTime scheduledLocalTime,
            @ForAll @BigRange(min = "0.00", max = "100000.00") BigDecimal discount) {

        PriceRequest request = new PriceRequest(SUBCATEGORY, emergency, surge, distanceKm,
                timeCharge, partsMaterials, scheduledLocalTime, null, null, discount);

        PriceBreakdown b = service.calculate(request, params);

        assertThat(b.basePrice()).isNotNull();
        assertThat(b.distanceCharge()).isNotNull();
        assertThat(b.timeCharge()).isNotNull();
        assertThat(b.partsMaterialsCharge()).isNotNull();
        assertThat(b.emergencyCharge()).isNotNull();
        assertThat(b.weekendSurcharge()).isNotNull();
        assertThat(b.nightSurcharge()).isNotNull();
        assertThat(b.demandSurgeCharge()).isNotNull();
        assertThat(b.platformFee()).isNotNull();
        assertThat(b.taxes()).isNotNull();
        assertThat(b.discountAmount()).isNotNull();
        assertThat(b.couponAmount()).isNotNull();
        assertThat(b.total()).isNotNull();

        assertThat(b.componentSum()).isEqualByComparingTo(b.total());
    }

    // ============================================================================================
    // Property 7: Provider-specific price override bounds (rejected iff outside [floor, ceiling])
    // ============================================================================================

    @Property(tries = 100)
    @Label("Feature: homefix-platform, Property 7: Provider-specific price override bounds")
    void overrideRejectedIffOutsideBounds(
            @ForAll @BigRange(min = "0.00", max = "1000.00") BigDecimal proposedPrice,
            @ForAll @BigRange(min = "0.00", max = "500.00") BigDecimal floor,
            @ForAll @BigRange(min = "0.00", max = "1000.00") BigDecimal ceiling) {

        PricingParameters params = new PricingParameters(SUBCATEGORY, new BigDecimal("100.00"),
                BigDecimal.ZERO, new BigDecimal("100.00"), BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, emergencyCap, surgeCap, floor, ceiling);

        boolean expectRejected = proposedPrice.compareTo(floor) < 0
                || proposedPrice.compareTo(ceiling) > 0;

        if (expectRejected) {
            assertThat(catchOverride(proposedPrice, params))
                    .as("expected rejection for price %s outside [%s, %s]", proposedPrice, floor, ceiling)
                    .isNotNull()
                    .satisfies(ex -> assertThat(ex.getErrorCode()).isEqualTo("OVERRIDE_OUT_OF_RANGE"));
        } else {
            assertThat(catchOverride(proposedPrice, params))
                    .as("expected acceptance for price %s within [%s, %s]", proposedPrice, floor, ceiling)
                    .isNull();
            assertThat(overrideService.validateOverride(proposedPrice, params))
                    .isEqualByComparingTo(proposedPrice);
        }
    }

    // ============================================================================================
    // Generators
    // ============================================================================================

    @Provide
    Arbitrary<BigDecimal> positiveBasePrice() {
        return Arbitraries.bigDecimals()
                .between(new BigDecimal("0.01"), new BigDecimal("10000.00"))
                .ofScale(2);
    }

    @Provide
    Arbitrary<BigDecimal> nonNegativeMoney() {
        return Arbitraries.bigDecimals()
                .between(BigDecimal.ZERO, new BigDecimal("1000.00"))
                .ofScale(2);
    }

    @Provide
    Arbitrary<LocalDateTime> localTimes() {
        Arbitrary<Integer> year = Arbitraries.integers().between(2024, 2026);
        Arbitrary<Integer> month = Arbitraries.integers().between(1, 12);
        Arbitrary<Integer> day = Arbitraries.integers().between(1, 28);
        Arbitrary<Integer> hour = Arbitraries.integers().between(0, 23);
        Arbitrary<Integer> minute = Arbitraries.integers().between(0, 59);
        Arbitrary<LocalDateTime> generated = Combinators.combine(year, month, day, hour, minute)
                .as((y, mo, d, h, mi) -> LocalDateTime.of(y, mo, d, h, mi));
        // Include the null case (no schedule => no night/weekend surcharge).
        return Arbitraries.oneOf(generated, Arbitraries.just(null));
    }

    @Provide
    Arbitrary<PricingParameters> params() {
        Arbitrary<BigDecimal> basePrice = Arbitraries.bigDecimals()
                .between(BigDecimal.ZERO, new BigDecimal("10000.00")).ofScale(2);
        Arbitrary<BigDecimal> perKmRate = Arbitraries.bigDecimals()
                .between(BigDecimal.ZERO, new BigDecimal("50.00")).ofScale(2);
        Arbitrary<BigDecimal> maxTravel = Arbitraries.bigDecimals()
                .between(BigDecimal.ZERO, new BigDecimal("500.00")).ofScale(2);
        Arbitrary<BigDecimal> nightSurcharge = Arbitraries.bigDecimals()
                .between(BigDecimal.ZERO, new BigDecimal("200.00")).ofScale(2);
        Arbitrary<BigDecimal> weekendSurcharge = Arbitraries.bigDecimals()
                .between(BigDecimal.ZERO, new BigDecimal("200.00")).ofScale(2);
        Arbitrary<BigDecimal> platformFeeRate = Arbitraries.bigDecimals()
                .between(BigDecimal.ZERO, new BigDecimal("0.30")).ofScale(4);
        Arbitrary<BigDecimal> taxRate = Arbitraries.bigDecimals()
                .between(BigDecimal.ZERO, new BigDecimal("0.30")).ofScale(4);
        Arbitrary<BigDecimal> emergencyMultiplier = Arbitraries.bigDecimals()
                .between(BigDecimal.ONE, new BigDecimal("6.0")).ofScale(2);
        Arbitrary<BigDecimal> surgeMultiplier = Arbitraries.bigDecimals()
                .between(BigDecimal.ONE, new BigDecimal("6.0")).ofScale(2);

        return Combinators.combine(basePrice, perKmRate, maxTravel, nightSurcharge,
                        weekendSurcharge, platformFeeRate, taxRate, emergencyMultiplier)
                .as((base, perKm, maxTrav, night, weekend, feeRate, tax, emg) ->
                        new Object[]{base, perKm, maxTrav, night, weekend, feeRate, tax, emg})
                .flatMap(front -> surgeMultiplier.map(surge -> new PricingParameters(
                        SUBCATEGORY,
                        (BigDecimal) front[0],
                        (BigDecimal) front[1],
                        (BigDecimal) front[2],
                        (BigDecimal) front[3],
                        (BigDecimal) front[4],
                        (BigDecimal) front[5],
                        (BigDecimal) front[6],
                        (BigDecimal) front[7],
                        surge,
                        new BigDecimal("10.00"),
                        new BigDecimal("500.00"))));
    }

    // ============================================================================================
    // Helpers
    // ============================================================================================

    private PricingParameters paramsFor(BigDecimal basePrice, BigDecimal emergency, BigDecimal surge) {
        return new PricingParameters(SUBCATEGORY, basePrice, BigDecimal.ZERO,
                new BigDecimal("100.00"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, emergency, surge, new BigDecimal("10.00"), new BigDecimal("500.00"));
    }

    private PriceRequest emergencyRequest() {
        return new PriceRequest(SUBCATEGORY, true, false, null, null, null, null, null, null, null);
    }

    private PriceRequest surgeRequest() {
        return new PriceRequest(SUBCATEGORY, false, true, null, null, null, null, null, null, null);
    }

    /** effective factor = 1 + charge / basePrice, at high precision for comparison. */
    private BigDecimal effectiveFactor(BigDecimal basePrice, BigDecimal charge) {
        return BigDecimal.ONE.add(charge.divide(basePrice, CALC_SCALE, RoundingMode.HALF_UP));
    }

    private BigDecimal round(BigDecimal value) {
        return value.setScale(properties.getMoneyScale(), properties.getMoneyRounding());
    }

    private BigDecimal clamp(BigDecimal value, BigDecimal min, BigDecimal max) {
        BigDecimal v = value.setScale(CLAMP_SCALE, RoundingMode.HALF_UP);
        if (v.compareTo(min) < 0) {
            return min;
        }
        if (v.compareTo(max) > 0) {
            return max;
        }
        return v;
    }

    private PricingException catchOverride(BigDecimal proposedPrice, PricingParameters params) {
        try {
            overrideService.validateOverride(proposedPrice, params);
            return null;
        } catch (PricingException ex) {
            return ex;
        }
    }
}
