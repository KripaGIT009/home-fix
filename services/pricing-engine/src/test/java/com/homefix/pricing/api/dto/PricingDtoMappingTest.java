package com.homefix.pricing.api.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.homefix.pricing.domain.PriceBreakdown;
import com.homefix.pricing.domain.PricingParameters;
import com.homefix.pricing.service.PriceRequest;

/**
 * Round-trip mapping tests for the REST DTOs (Requirement 6.9, 6.11). Each DTO's
 * {@code toDomain}/{@code from} converter must preserve every field so the wire contract and
 * the domain model stay in lock-step.
 */
class PricingDtoMappingTest {

    private static final UUID SUB = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID USER = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Test
    void priceRequestDtoMapsEveryFieldToDomain() {
        LocalDateTime when = LocalDateTime.of(2024, 6, 15, 23, 30);
        PriceRequestDto dto = new PriceRequestDto(SUB, true, true, new BigDecimal("5.0"),
                new BigDecimal("15.00"), new BigDecimal("25.00"), when, "SAVE10", USER,
                new BigDecimal("3.00"));

        PriceRequest domain = dto.toDomain();

        assertThat(domain.subcategoryId()).isEqualTo(SUB);
        assertThat(domain.emergency()).isTrue();
        assertThat(domain.surgeActive()).isTrue();
        assertThat(domain.distanceKm()).isEqualByComparingTo("5.0");
        assertThat(domain.timeCharge()).isEqualByComparingTo("15.00");
        assertThat(domain.partsMaterialsCharge()).isEqualByComparingTo("25.00");
        assertThat(domain.scheduledLocalTime()).isEqualTo(when);
        assertThat(domain.couponCode()).isEqualTo("SAVE10");
        assertThat(domain.userId()).isEqualTo(USER);
        assertThat(domain.orderDiscount()).isEqualByComparingTo("3.00");
    }

    @Test
    void pricingParametersDtoRoundTripsThroughDomain() {
        PricingParametersDto dto = new PricingParametersDto(SUB, new BigDecimal("100.00"),
                new BigDecimal("2.00"), new BigDecimal("50.00"), new BigDecimal("20.00"),
                new BigDecimal("30.00"), new BigDecimal("0.10"), new BigDecimal("0.05"),
                new BigDecimal("2.0"), new BigDecimal("2.0"), new BigDecimal("10.00"),
                new BigDecimal("500.00"));

        PricingParameters domain = dto.toDomain();
        PricingParametersDto back = PricingParametersDto.from(domain);

        assertThat(back).isEqualTo(dto);
        assertThat(domain.subcategoryId()).isEqualTo(SUB);
        assertThat(domain.basePrice()).isEqualByComparingTo("100.00");
        assertThat(domain.perKmRate()).isEqualByComparingTo("2.00");
        assertThat(domain.maxTravelCharge()).isEqualByComparingTo("50.00");
        assertThat(domain.platformFeeRate()).isEqualByComparingTo("0.10");
        assertThat(domain.taxRate()).isEqualByComparingTo("0.05");
        assertThat(domain.overrideCeiling()).isEqualByComparingTo("500.00");
    }

    @Test
    void priceBreakdownDtoCopiesEveryComponentFromDomain() {
        PriceBreakdown b = PriceBreakdown.builder()
                .basePrice(new BigDecimal("100.00"))
                .distanceCharge(new BigDecimal("10.00"))
                .timeCharge(new BigDecimal("15.00"))
                .partsMaterialsCharge(new BigDecimal("25.00"))
                .emergencyCharge(new BigDecimal("50.00"))
                .weekendSurcharge(new BigDecimal("30.00"))
                .nightSurcharge(new BigDecimal("20.00"))
                .demandSurgeCharge(new BigDecimal("40.00"))
                .platformFee(new BigDecimal("5.00"))
                .taxes(new BigDecimal("2.00"))
                .discountAmount(new BigDecimal("3.00"))
                .couponAmount(new BigDecimal("1.00"))
                .build(new com.homefix.pricing.domain.Money(2, java.math.RoundingMode.HALF_UP),
                        new BigDecimal("0.01"));

        PriceBreakdownDto dto = PriceBreakdownDto.from(b);

        assertThat(dto.basePrice()).isEqualByComparingTo(b.basePrice());
        assertThat(dto.distanceCharge()).isEqualByComparingTo(b.distanceCharge());
        assertThat(dto.timeCharge()).isEqualByComparingTo(b.timeCharge());
        assertThat(dto.partsMaterialsCharge()).isEqualByComparingTo(b.partsMaterialsCharge());
        assertThat(dto.emergencyCharge()).isEqualByComparingTo(b.emergencyCharge());
        assertThat(dto.weekendSurcharge()).isEqualByComparingTo(b.weekendSurcharge());
        assertThat(dto.nightSurcharge()).isEqualByComparingTo(b.nightSurcharge());
        assertThat(dto.demandSurgeCharge()).isEqualByComparingTo(b.demandSurgeCharge());
        assertThat(dto.platformFee()).isEqualByComparingTo(b.platformFee());
        assertThat(dto.taxes()).isEqualByComparingTo(b.taxes());
        assertThat(dto.discountAmount()).isEqualByComparingTo(b.discountAmount());
        assertThat(dto.couponAmount()).isEqualByComparingTo(b.couponAmount());
        assertThat(dto.total()).isEqualByComparingTo(b.total());
    }

    @Test
    void overrideRequestDtoRetainsFields() {
        OverrideRequestDto dto = new OverrideRequestDto(SUB, new BigDecimal("120.00"));

        assertThat(dto.subcategoryId()).isEqualTo(SUB);
        assertThat(dto.proposedPrice()).isEqualByComparingTo("120.00");
    }

    @Test
    void pricingConfigDtoShowsThePlatformFeeAsAPercentAndLeavesCatalogNamesNull() {
        PricingParameters p = new PricingParameters(SUB, new BigDecimal("100.00"),
                new BigDecimal("2.00"), new BigDecimal("50.00"), new BigDecimal("20.00"),
                new BigDecimal("30.00"), new BigDecimal("0.150000"), new BigDecimal("0.18"),
                new BigDecimal("2.0"), new BigDecimal("1.5"), new BigDecimal("10.00"),
                new BigDecimal("500.00"));

        PricingConfigDto dto = PricingConfigDto.from(p);

        assertThat(dto.platformFeePercent().toPlainString()).isEqualTo("15");
        assertThat(dto.emergencyMultiplierCap()).isEqualByComparingTo("2.0");
        assertThat(dto.surgeMultiplierCap()).isEqualByComparingTo("1.5");
        assertThat(dto.currency()).isEqualTo("INR");
        assertThat(dto.subcategoryName()).isNull();
        assertThat(dto.categoryName()).isNull();
    }

    @Test
    void pricingConfigDtoPercentConversionKeepsAPlainScale() {
        assertThat(PricingConfigDto.fractionToPercent(new BigDecimal("1.000000")).toPlainString())
                .isEqualTo("100");
        assertThat(PricingConfigDto.fractionToPercent(new BigDecimal("0.125")).toPlainString())
                .isEqualTo("12.5");
        assertThat(PricingConfigDto.fractionToPercent(null)).isNull();
        assertThat(PricingConfigDto.percentToFraction(new BigDecimal("15"))).isEqualByComparingTo("0.15");
    }

    @Test
    void pricingConfigDtoChangesCarryAFractionAndLeaveUnmanagedFieldsNull() {
        PricingConfigDto dto = new PricingConfigDto(SUB, "Tap repair", "Plumbing",
                new BigDecimal("120.00"), new BigDecimal("3.00"), null, new BigDecimal("12.5"),
                new BigDecimal("10.00"), new BigDecimal("15.00"), new BigDecimal("1.8"),
                new BigDecimal("1.4"), "INR");

        PricingParameters changes = dto.toChanges(SUB);

        assertThat(changes.subcategoryId()).isEqualTo(SUB);
        assertThat(changes.basePrice()).isEqualByComparingTo("120.00");
        assertThat(changes.platformFeeRate()).isEqualByComparingTo("0.125");
        assertThat(changes.emergencyMultiplier()).isEqualByComparingTo("1.8");
        assertThat(changes.surgeMultiplier()).isEqualByComparingTo("1.4");
        assertThat(changes.maxTravelCharge()).isNull();
        // Not managed by the portal: null means "keep the stored value" in the merge.
        assertThat(changes.taxRate()).isNull();
        assertThat(changes.overrideFloor()).isNull();
        assertThat(changes.overrideCeiling()).isNull();
    }
}
