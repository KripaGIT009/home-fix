package com.homefix.pricing.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * A fully itemised price breakdown (Requirement 6.9, Property 6).
 *
 * <p>Every component is non-null. The invariant enforced by {@link #build(Money)} is:
 * <pre>
 *   total = basePrice + distanceCharge + timeCharge + partsMaterialsCharge + emergencyCharge
 *         + weekendSurcharge + nightSurcharge + demandSurgeCharge + platformFee + taxes
 *         − discountAmount − couponAmount
 * </pre>
 * so the sum of the itemised components exactly equals {@code total}, and {@code total} is
 * never below the configured minimum (Requirement 6.1, Property 1).
 */
public record PriceBreakdown(
        BigDecimal basePrice,
        BigDecimal distanceCharge,
        BigDecimal timeCharge,
        BigDecimal partsMaterialsCharge,
        BigDecimal emergencyCharge,
        BigDecimal weekendSurcharge,
        BigDecimal nightSurcharge,
        BigDecimal demandSurgeCharge,
        BigDecimal platformFee,
        BigDecimal taxes,
        BigDecimal discountAmount,
        BigDecimal couponAmount,
        BigDecimal total) {

    public PriceBreakdown {
        requireNonNull("basePrice", basePrice);
        requireNonNull("distanceCharge", distanceCharge);
        requireNonNull("timeCharge", timeCharge);
        requireNonNull("partsMaterialsCharge", partsMaterialsCharge);
        requireNonNull("emergencyCharge", emergencyCharge);
        requireNonNull("weekendSurcharge", weekendSurcharge);
        requireNonNull("nightSurcharge", nightSurcharge);
        requireNonNull("demandSurgeCharge", demandSurgeCharge);
        requireNonNull("platformFee", platformFee);
        requireNonNull("taxes", taxes);
        requireNonNull("discountAmount", discountAmount);
        requireNonNull("couponAmount", couponAmount);
        requireNonNull("total", total);
    }

    private static void requireNonNull(String field, BigDecimal value) {
        if (value == null) {
            throw new IllegalArgumentException("price component '" + field + "' must not be null");
        }
    }

    /** The positive (added) components, in itemisation order. */
    public List<BigDecimal> additiveComponents() {
        List<BigDecimal> components = new ArrayList<>();
        components.add(basePrice);
        components.add(distanceCharge);
        components.add(timeCharge);
        components.add(partsMaterialsCharge);
        components.add(emergencyCharge);
        components.add(weekendSurcharge);
        components.add(nightSurcharge);
        components.add(demandSurgeCharge);
        components.add(platformFee);
        components.add(taxes);
        return components;
    }

    /**
     * @return the arithmetic sum of the components (additive minus discount and coupon). By the
     *         build-time invariant this equals {@link #total()}.
     */
    public BigDecimal componentSum() {
        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal c : additiveComponents()) {
            sum = sum.add(c);
        }
        return sum.subtract(discountAmount).subtract(couponAmount);
    }

    /** Mutable builder used by the pricing service; normalises every field through {@link Money}. */
    public static final class Builder {
        private BigDecimal basePrice = BigDecimal.ZERO;
        private BigDecimal distanceCharge = BigDecimal.ZERO;
        private BigDecimal timeCharge = BigDecimal.ZERO;
        private BigDecimal partsMaterialsCharge = BigDecimal.ZERO;
        private BigDecimal emergencyCharge = BigDecimal.ZERO;
        private BigDecimal weekendSurcharge = BigDecimal.ZERO;
        private BigDecimal nightSurcharge = BigDecimal.ZERO;
        private BigDecimal demandSurgeCharge = BigDecimal.ZERO;
        private BigDecimal platformFee = BigDecimal.ZERO;
        private BigDecimal taxes = BigDecimal.ZERO;
        private BigDecimal discountAmount = BigDecimal.ZERO;
        private BigDecimal couponAmount = BigDecimal.ZERO;

        public Builder basePrice(BigDecimal v) { this.basePrice = v; return this; }
        public Builder distanceCharge(BigDecimal v) { this.distanceCharge = v; return this; }
        public Builder timeCharge(BigDecimal v) { this.timeCharge = v; return this; }
        public Builder partsMaterialsCharge(BigDecimal v) { this.partsMaterialsCharge = v; return this; }
        public Builder emergencyCharge(BigDecimal v) { this.emergencyCharge = v; return this; }
        public Builder weekendSurcharge(BigDecimal v) { this.weekendSurcharge = v; return this; }
        public Builder nightSurcharge(BigDecimal v) { this.nightSurcharge = v; return this; }
        public Builder demandSurgeCharge(BigDecimal v) { this.demandSurgeCharge = v; return this; }
        public Builder platformFee(BigDecimal v) { this.platformFee = v; return this; }
        public Builder taxes(BigDecimal v) { this.taxes = v; return this; }
        public Builder discountAmount(BigDecimal v) { this.discountAmount = v; return this; }
        public Builder couponAmount(BigDecimal v) { this.couponAmount = v; return this; }

        /**
         * Rounds every component through {@code money} and computes the total as their sum.
         *
         * <p>If the summed total is below {@code minTotal} it is raised to {@code minTotal};
         * the shortfall is absorbed into the discount line so the itemised components still sum
         * to the returned total (Property 6) while never dropping below the floor (Property 1).
         */
        public PriceBreakdown build(Money money, BigDecimal minTotal) {
            BigDecimal rBase = money.roundOrZero(basePrice);
            BigDecimal rDistance = money.roundOrZero(distanceCharge);
            BigDecimal rTime = money.roundOrZero(timeCharge);
            BigDecimal rParts = money.roundOrZero(partsMaterialsCharge);
            BigDecimal rEmergency = money.roundOrZero(emergencyCharge);
            BigDecimal rWeekend = money.roundOrZero(weekendSurcharge);
            BigDecimal rNight = money.roundOrZero(nightSurcharge);
            BigDecimal rSurge = money.roundOrZero(demandSurgeCharge);
            BigDecimal rPlatform = money.roundOrZero(platformFee);
            BigDecimal rTaxes = money.roundOrZero(taxes);
            BigDecimal rDiscount = money.roundOrZero(discountAmount);
            BigDecimal rCoupon = money.roundOrZero(couponAmount);

            BigDecimal additive = rBase.add(rDistance).add(rTime).add(rParts).add(rEmergency)
                    .add(rWeekend).add(rNight).add(rSurge).add(rPlatform).add(rTaxes);
            BigDecimal total = additive.subtract(rDiscount).subtract(rCoupon);

            BigDecimal floor = money.round(minTotal);
            if (total.compareTo(floor) < 0) {
                // Reduce the deduction so the total lands exactly on the floor while the
                // components continue to sum to the total.
                BigDecimal shortfall = floor.subtract(total);
                rDiscount = rDiscount.subtract(shortfall);
                total = floor;
            }

            return new PriceBreakdown(rBase, rDistance, rTime, rParts, rEmergency, rWeekend,
                    rNight, rSurge, rPlatform, rTaxes, rDiscount, rCoupon, money.round(total));
        }
    }

    public static Builder builder() {
        return new Builder();
    }
}
