package com.homefix.admin.dispatch;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * The five dispatch matching weights an Admin may configure (Requirement 19.5, Property 19).
 *
 * <p>A valid weight set requires every weight in [0.0, 1.0] and an exact sum of 1.0. Construction
 * goes through {@link #ofExact}, so an invalid set can never exist as a {@code DispatchWeights}
 * value — a rejected update throws {@link WeightValidationException} before any store mutation,
 * which is how "leave the existing weights unchanged" is guaranteed.
 *
 * <p>"Sum equals 1.0 exactly" is evaluated with {@link BigDecimal} on the decimals the Admin
 * actually submitted, avoiding binary floating-point artefacts (e.g. {@code 0.1 + 0.2}).
 */
public record DispatchWeights(
        BigDecimal distanceWeight,
        BigDecimal availabilityWeight,
        BigDecimal ratingWeight,
        BigDecimal skillWeight,
        BigDecimal performanceWeight) {

    /** Platform default weights (Requirement 8.3). */
    public static final DispatchWeights DEFAULT = new DispatchWeights(
            new BigDecimal("0.30"),
            new BigDecimal("0.25"),
            new BigDecimal("0.20"),
            new BigDecimal("0.15"),
            new BigDecimal("0.10"));

    private static final BigDecimal ONE = BigDecimal.ONE;

    /**
     * Validates and constructs a weight set. Each weight is checked against [0.0, 1.0] and the sum
     * against 1.0 with no tolerance.
     *
     * @throws WeightValidationException if any weight is out of range or the sum is not 1.0
     */
    public static DispatchWeights ofExact(BigDecimal distance,
                                          BigDecimal availability,
                                          BigDecimal rating,
                                          BigDecimal skill,
                                          BigDecimal performance) {
        require("distanceWeight", distance);
        require("availabilityWeight", availability);
        require("ratingWeight", rating);
        require("skillWeight", skill);
        require("performanceWeight", performance);

        BigDecimal sum = distance.add(availability).add(rating).add(skill).add(performance);
        if (sum.compareTo(ONE) != 0) {
            throw new WeightValidationException(
                    "weights must sum to exactly 1.0 but summed to "
                            + sum.stripTrailingZeros().toPlainString());
        }
        return new DispatchWeights(distance, availability, rating, skill, performance);
    }

    private static void require(String name, BigDecimal value) {
        Objects.requireNonNull(value, () -> name + " must not be null");
        if (value.compareTo(BigDecimal.ZERO) < 0 || value.compareTo(ONE) > 0) {
            throw new WeightValidationException(
                    name + " must be in [0.0, 1.0] but was " + value.stripTrailingZeros().toPlainString());
        }
    }
}
