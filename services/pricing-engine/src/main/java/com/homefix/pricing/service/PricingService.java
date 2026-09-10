package com.homefix.pricing.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;

import org.springframework.stereotype.Service;

import com.homefix.pricing.config.PricingProperties;
import com.homefix.pricing.domain.Money;
import com.homefix.pricing.domain.PriceBreakdown;
import com.homefix.pricing.domain.PricingParameters;

/**
 * Core price calculation (Requirement 6.1–6.9).
 *
 * <p>Produces a fully itemised {@link PriceBreakdown} whose components sum to the returned total
 * (Property 6) and whose total is never below the configured floor (Property 1). All monetary
 * math uses {@link BigDecimal} normalised through {@link Money}. Coupon validation
 * (Requirement 6.10) and provider-override bounds (Requirement 6.12) are handled by
 * {@link CouponService} and {@link ProviderOverrideService} respectively and are not part of
 * the raw formula here.
 *
 * <p>Multiplier semantics (Requirement 6.2–6.4):
 * <ul>
 *   <li>Emergency multiplier is clamped to {@code [1, maxEmergencyMultiplier]} (default cap 2.0×).</li>
 *   <li>Surge multiplier is clamped to {@code [1, maxSurgeMultiplier]} (default cap 2.0×).</li>
 *   <li>When both apply, emergency is applied first, then surge:
 *       {@code effective = base × emergency × surge}, and the combined effective factor is
 *       capped at {@code emergencyCap + surgeCap}. The surcharge is decomposed into an emergency
 *       component and a surge component so the breakdown stays itemised.</li>
 * </ul>
 */
@Service
public class PricingService {

    private static final BigDecimal ONE = BigDecimal.ONE;
    private static final LocalTime NIGHT_START = LocalTime.of(22, 0);
    private static final LocalTime NIGHT_END = LocalTime.of(6, 0);
    /** Precision used for intermediate multiplier arithmetic before monetary rounding. */
    private static final int CALC_SCALE = 10;

    private final PricingProperties properties;
    private final Money money;

    public PricingService(PricingProperties properties) {
        this.properties = properties;
        this.money = new Money(properties.getMoneyScale(), properties.getMoneyRounding());
    }

    public Money money() {
        return money;
    }

    /** Calculates the itemised breakdown for a request using the resolved subcategory parameters. */
    public PriceBreakdown calculate(PriceRequest request, PricingParameters params) {
        BigDecimal base = nonNegative(params.basePrice());

        // ----- Multipliers (emergency first, then surge), with the combined cap. -----
        BigDecimal emergencyCharge = BigDecimal.ZERO;
        BigDecimal surgeCharge = BigDecimal.ZERO;

        BigDecimal emgFactor = request.emergency()
                ? clamp(orOne(params.emergencyMultiplier()), ONE, properties.getMaxEmergencyMultiplier())
                : ONE;
        BigDecimal surgeFactor = request.surgeActive()
                ? clamp(orOne(params.surgeMultiplier()), ONE, properties.getMaxSurgeMultiplier())
                : ONE;

        if (request.emergency() || request.surgeActive()) {
            // Combined effective factor, capped at the sum of the two configured caps.
            BigDecimal effective = emgFactor.multiply(surgeFactor);
            BigDecimal combinedCap = properties.getMaxEmergencyMultiplier()
                    .add(properties.getMaxSurgeMultiplier());
            if (effective.compareTo(combinedCap) > 0) {
                effective = combinedCap;
            }
            // Total surcharge over base attributable to multipliers.
            BigDecimal totalSurcharge = base.multiply(effective.subtract(ONE));

            // Decompose: emergency component = base × (emgFactor − 1); the remainder is surge.
            emergencyCharge = base.multiply(emgFactor.subtract(ONE));
            if (emergencyCharge.compareTo(totalSurcharge) > 0) {
                emergencyCharge = totalSurcharge;
            }
            surgeCharge = totalSurcharge.subtract(emergencyCharge);
        }

        // ----- Distance charge (Requirement 6.7), capped at max travel charge. -----
        BigDecimal distanceCharge = distanceCharge(request.distanceKm(), params);

        // ----- Night / weekend surcharges (Requirement 6.5, 6.6). -----
        BigDecimal nightSurcharge = isNight(request.scheduledLocalTime())
                ? nonNegative(params.nightSurcharge()) : BigDecimal.ZERO;
        BigDecimal weekendSurcharge = isWeekend(request.scheduledLocalTime())
                ? nonNegative(params.weekendSurcharge()) : BigDecimal.ZERO;

        BigDecimal timeCharge = nonNegative(request.timeCharge());
        BigDecimal parts = nonNegative(request.partsMaterialsCharge());

        // ----- Platform fee and taxes computed on the pre-fee subtotal. -----
        BigDecimal subtotal = base
                .add(distanceCharge)
                .add(timeCharge)
                .add(parts)
                .add(emergencyCharge)
                .add(surgeCharge)
                .add(nightSurcharge)
                .add(weekendSurcharge);
        BigDecimal platformFee = subtotal.multiply(fraction(params.platformFeeRate()));
        BigDecimal taxes = subtotal.add(platformFee).multiply(fraction(params.taxRate()));

        BigDecimal discount = nonNegative(request.orderDiscount());

        return PriceBreakdown.builder()
                .basePrice(base)
                .distanceCharge(distanceCharge)
                .timeCharge(timeCharge)
                .partsMaterialsCharge(parts)
                .emergencyCharge(emergencyCharge)
                .weekendSurcharge(weekendSurcharge)
                .nightSurcharge(nightSurcharge)
                .demandSurgeCharge(surgeCharge)
                .platformFee(platformFee)
                .taxes(taxes)
                .discountAmount(discount)
                .couponAmount(BigDecimal.ZERO)
                .build(money, properties.getMinTotal());
    }

    /** distance_charge = distance_km × per_km_rate, capped at max_travel_charge (Requirement 6.7). */
    BigDecimal distanceCharge(BigDecimal distanceKm, PricingParameters params) {
        if (distanceKm == null || distanceKm.signum() <= 0 || params.perKmRate() == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal charge = distanceKm.multiply(params.perKmRate());
        BigDecimal cap = params.maxTravelCharge();
        if (cap != null && charge.compareTo(cap) > 0) {
            return cap;
        }
        return charge;
    }

    boolean isNight(LocalDateTime local) {
        if (local == null) {
            return false;
        }
        LocalTime t = local.toLocalTime();
        // Night window wraps midnight: [22:00, 24:00) ∪ [00:00, 06:00).
        return !t.isBefore(NIGHT_START) || t.isBefore(NIGHT_END);
    }

    boolean isWeekend(LocalDateTime local) {
        if (local == null) {
            return false;
        }
        DayOfWeek day = local.getDayOfWeek();
        return day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY;
    }

    private static BigDecimal orOne(BigDecimal v) {
        return v == null ? ONE : v;
    }

    private static BigDecimal nonNegative(BigDecimal v) {
        return (v == null || v.signum() < 0) ? BigDecimal.ZERO : v;
    }

    private static BigDecimal fraction(BigDecimal rate) {
        return (rate == null || rate.signum() < 0) ? BigDecimal.ZERO : rate;
    }

    private static BigDecimal clamp(BigDecimal value, BigDecimal min, BigDecimal max) {
        BigDecimal v = value.setScale(CALC_SCALE, RoundingMode.HALF_UP);
        if (v.compareTo(min) < 0) {
            return min;
        }
        if (v.compareTo(max) > 0) {
            return max;
        }
        return v;
    }
}
