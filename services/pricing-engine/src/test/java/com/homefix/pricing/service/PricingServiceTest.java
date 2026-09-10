package com.homefix.pricing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static com.homefix.pricing.support.TestData.SUBCATEGORY;
import static com.homefix.pricing.support.TestData.baseParams;
import static com.homefix.pricing.support.TestData.withDistance;
import static com.homefix.pricing.support.TestData.withMultipliers;
import static com.homefix.pricing.support.TestData.withSurcharges;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.homefix.pricing.config.PricingProperties;
import com.homefix.pricing.domain.PriceBreakdown;
import com.homefix.pricing.domain.PricingParameters;

/**
 * Example-based unit tests for the core pricing formula (Requirement 6.1–6.9). Properties 1–7
 * are exercised as universal properties by the dedicated PBT task (Task 40); here we assert
 * representative examples and boundary values.
 */
class PricingServiceTest {

    private final PricingProperties props = new PricingProperties();
    private final PricingService service = new PricingService(props);

    private PriceRequest request(boolean emergency, boolean surge) {
        return new PriceRequest(SUBCATEGORY, emergency, surge, null, null, null, null, null, null, null);
    }

    // ===================== Requirement 6.1 / Property 1: minimum total floor =====================

    @Nested
    class MinimumTotal {

        @Test
        void totalNeverBelowFloor_whenDiscountExceedsCharges() {
            // base 10, discount 1000 => raw total negative; must clamp to 0.01.
            PriceRequest req = new PriceRequest(SUBCATEGORY, false, false, null, null, null, null,
                    null, null, new BigDecimal("1000.00"));

            PriceBreakdown b = service.calculate(req, baseParams("10.00"));

            assertThat(b.total()).isEqualByComparingTo("0.01");
            assertThat(b.total()).isGreaterThanOrEqualTo(new BigDecimal("0.01"));
        }

        @Test
        void componentsAlwaysSumToTotal_evenAfterFloorClamp() {
            PriceRequest req = new PriceRequest(SUBCATEGORY, false, false, null, null, null, null,
                    null, null, new BigDecimal("1000.00"));

            PriceBreakdown b = service.calculate(req, baseParams("10.00"));

            assertThat(b.componentSum()).isEqualByComparingTo(b.total());
        }
    }

    // ===================== Requirement 6.2 / Property 2: emergency multiplier cap =====================

    @Nested
    class EmergencyMultiplier {

        @Test
        void emergencyChargeReflectsMultiplier_belowCap() {
            // base 100, emergency 1.5x => emergency charge = 100 * 0.5 = 50.
            PriceBreakdown b = service.calculate(request(true, false),
                    withMultipliers("100.00", "1.5", "1.0"));

            assertThat(b.emergencyCharge()).isEqualByComparingTo("50.00");
        }

        @Test
        void emergencyMultiplierCappedAtConfiguredMax() {
            // configured emergency multiplier 3.0 but cap is 2.0 => emergency charge = 100 * 1.0.
            PriceBreakdown b = service.calculate(request(true, false),
                    withMultipliers("100.00", "3.0", "1.0"));

            assertThat(b.emergencyCharge()).isEqualByComparingTo("100.00");
        }

        @Test
        void noEmergencyChargeWhenNotEmergency() {
            PriceBreakdown b = service.calculate(request(false, false),
                    withMultipliers("100.00", "2.0", "1.0"));

            assertThat(b.emergencyCharge()).isEqualByComparingTo("0.00");
        }
    }

    // ===================== Requirement 6.3 / Property 3: surge multiplier cap =====================

    @Nested
    class SurgeMultiplier {

        @Test
        void surgeChargeReflectsMultiplier_belowCap() {
            PriceBreakdown b = service.calculate(request(false, true),
                    withMultipliers("100.00", "1.0", "1.5"));

            assertThat(b.demandSurgeCharge()).isEqualByComparingTo("50.00");
            assertThat(b.emergencyCharge()).isEqualByComparingTo("0.00");
        }

        @Test
        void surgeMultiplierCappedAtConfiguredMax() {
            PriceBreakdown b = service.calculate(request(false, true),
                    withMultipliers("100.00", "1.0", "4.0"));

            // capped at 2.0 => surge charge = 100 * 1.0.
            assertThat(b.demandSurgeCharge()).isEqualByComparingTo("100.00");
        }
    }

    // ===================== Requirement 6.4 / Property 4: combined multiplier ordering & cap ==========

    @Nested
    class CombinedMultipliers {

        @Test
        void emergencyAppliedFirstThenSurge() {
            // base 100, emergency 1.5x then surge 1.2x => effective 1.8x => surcharge 80.
            // emergency component = 100*(1.5-1)=50; surge component = 80-50 = 30.
            PriceBreakdown b = service.calculate(request(true, true),
                    withMultipliers("100.00", "1.5", "1.2"));

            assertThat(b.emergencyCharge()).isEqualByComparingTo("50.00");
            assertThat(b.demandSurgeCharge()).isEqualByComparingTo("30.00");
            assertThat(b.emergencyCharge().add(b.demandSurgeCharge())).isEqualByComparingTo("80.00");
        }

        @Test
        void combinedEffectiveMultiplierCappedAtSumOfCaps() {
            // emergency 2.0 * surge 2.0 = 4.0 effective, combined cap = 2.0 + 2.0 = 4.0.
            // surcharge over base = 100 * (4.0 - 1) = 300.
            PriceBreakdown b = service.calculate(request(true, true),
                    withMultipliers("100.00", "2.0", "2.0"));

            BigDecimal surcharge = b.emergencyCharge().add(b.demandSurgeCharge());
            assertThat(surcharge).isEqualByComparingTo("300.00");
        }

        @Test
        void effectiveMultiplierNeverExceedsCombinedCap_evenWithHugeConfiguredValues() {
            // Configured 5.0 * 5.0 but each clamps to 2.0, product 4.0 == combined cap 4.0.
            PriceBreakdown b = service.calculate(request(true, true),
                    withMultipliers("100.00", "5.0", "5.0"));

            BigDecimal surcharge = b.emergencyCharge().add(b.demandSurgeCharge());
            // combined cap = 4.0 => surcharge = 100 * 3.0 = 300.
            assertThat(surcharge).isEqualByComparingTo("300.00");
        }
    }

    // ===================== Requirement 6.7 / Property 5: distance charge cap =====================

    @Nested
    class DistanceCharge {

        @Test
        void distanceChargeIsDistanceTimesRate_belowCap() {
            PriceRequest req = new PriceRequest(SUBCATEGORY, false, false, new BigDecimal("5"),
                    null, null, null, null, null, null);

            PriceBreakdown b = service.calculate(req, withDistance("100.00", "2.00", "50.00"));

            assertThat(b.distanceCharge()).isEqualByComparingTo("10.00");
        }

        @Test
        void distanceChargeCappedAtMaxTravelCharge() {
            PriceRequest req = new PriceRequest(SUBCATEGORY, false, false, new BigDecimal("100"),
                    null, null, null, null, null, null);

            PriceBreakdown b = service.calculate(req, withDistance("100.00", "2.00", "50.00"));

            // 100 * 2 = 200, capped at 50.
            assertThat(b.distanceCharge()).isEqualByComparingTo("50.00");
        }

        @Test
        void zeroDistanceYieldsZeroCharge() {
            PriceRequest req = new PriceRequest(SUBCATEGORY, false, false, BigDecimal.ZERO,
                    null, null, null, null, null, null);

            PriceBreakdown b = service.calculate(req, withDistance("100.00", "2.00", "50.00"));

            assertThat(b.distanceCharge()).isEqualByComparingTo("0.00");
        }
    }

    // ===================== Requirement 6.5 / 6.6: night & weekend surcharge toggling ================

    @Nested
    class NightWeekendSurcharge {

        // 2024-01-03 is a Wednesday.
        private final LocalDateTime wednesdayNoon = LocalDateTime.of(2024, 1, 3, 12, 0);
        private final LocalDateTime wednesdayNight = LocalDateTime.of(2024, 1, 3, 23, 0);
        private final LocalDateTime wednesdayEarly = LocalDateTime.of(2024, 1, 3, 5, 0);
        // 2024-01-06 is a Saturday.
        private final LocalDateTime saturdayNoon = LocalDateTime.of(2024, 1, 6, 12, 0);

        private PriceRequest at(LocalDateTime t) {
            return new PriceRequest(SUBCATEGORY, false, false, null, null, null, t, null, null, null);
        }

        @Test
        void nightSurchargeAppliedBetween22And06() {
            PricingParameters p = withSurcharges("100.00", "20.00", "30.00");

            assertThat(service.calculate(at(wednesdayNight), p).nightSurcharge())
                    .isEqualByComparingTo("20.00");
            assertThat(service.calculate(at(wednesdayEarly), p).nightSurcharge())
                    .isEqualByComparingTo("20.00");
        }

        @Test
        void nightSurchargeNotAppliedDuringDay() {
            PricingParameters p = withSurcharges("100.00", "20.00", "30.00");

            assertThat(service.calculate(at(wednesdayNoon), p).nightSurcharge())
                    .isEqualByComparingTo("0.00");
        }

        @Test
        void weekendSurchargeAppliedOnSaturday() {
            PricingParameters p = withSurcharges("100.00", "20.00", "30.00");

            assertThat(service.calculate(at(saturdayNoon), p).weekendSurcharge())
                    .isEqualByComparingTo("30.00");
        }

        @Test
        void weekendSurchargeNotAppliedOnWeekday() {
            PricingParameters p = withSurcharges("100.00", "20.00", "30.00");

            assertThat(service.calculate(at(wednesdayNoon), p).weekendSurcharge())
                    .isEqualByComparingTo("0.00");
        }

        @Test
        void nullScheduleAppliesNeitherSurcharge() {
            PricingParameters p = withSurcharges("100.00", "20.00", "30.00");

            PriceBreakdown b = service.calculate(at(null), p);
            assertThat(b.nightSurcharge()).isEqualByComparingTo("0.00");
            assertThat(b.weekendSurcharge()).isEqualByComparingTo("0.00");
        }
    }

    // ===================== Requirement 6.9 / Property 6: itemised completeness =====================

    @Nested
    class ItemisedBreakdown {

        @Test
        void allComponentsNonNull() {
            PriceBreakdown b = service.calculate(request(true, true), baseParams("100.00"));

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
        }

        @Test
        void componentsSumToTotal_withFeesAndTaxes() {
            // base 100 with 10% platform fee and 5% tax, saturday night.
            PricingParameters p = new PricingParameters(SUBCATEGORY, new BigDecimal("100.00"),
                    BigDecimal.ZERO, new BigDecimal("100.00"), new BigDecimal("20.00"),
                    new BigDecimal("30.00"), new BigDecimal("0.10"), new BigDecimal("0.05"),
                    new BigDecimal("2.0"), new BigDecimal("2.0"),
                    new BigDecimal("10.00"), new BigDecimal("500.00"));
            PriceRequest req = new PriceRequest(SUBCATEGORY, false, false, null,
                    new BigDecimal("15.00"), new BigDecimal("25.00"),
                    LocalDateTime.of(2024, 1, 6, 23, 0), null, null, null);

            PriceBreakdown b = service.calculate(req, p);

            assertThat(b.componentSum()).isEqualByComparingTo(b.total());
        }
    }
}
