package com.homefix.dispatch.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * The five weights applied to the {@link ScoreComponents} to produce a provider's matching
 * score (Requirements 8.3, 8.4, 19.5; Properties 18, 19).
 *
 * <p>An instance is valid only if every weight is in [0.0, 1.0] and the five weights sum to
 * exactly 1.0. Construction goes through {@link #of} (or {@link #ofExact}) so an invalid weight
 * set can never exist as a {@code MatchingWeights} value — callers hold either the previous
 * valid weights or a validation error, never a half-valid object.
 *
 * <p>Because Admin input arrives as decimal numbers, "sum equals 1.0 exactly" is evaluated with
 * {@link BigDecimal} to avoid binary floating-point artefacts (e.g. {@code 0.1 + 0.2}). The
 * validated values are then stored as {@code double} for use in scoring, which is unitless.
 */
public record MatchingWeights(
        double distanceWeight,
        double availabilityWeight,
        double ratingWeight,
        double skillWeight,
        double performanceWeight) {

    /** Platform default weights (Requirement 8.3). */
    public static final MatchingWeights DEFAULT =
            new MatchingWeights(0.30, 0.25, 0.20, 0.15, 0.10);

    private static final BigDecimal ONE = BigDecimal.ONE;

    /**
     * Validates and constructs a weight set from {@link BigDecimal} inputs (the natural type for
     * Admin-submitted decimals). This is the strictest entry point: the sum is compared to 1.0
     * without any tolerance.
     *
     * @throws WeightValidationException if any weight is outside [0.0, 1.0] or the sum is not 1.0
     */
    public static MatchingWeights ofExact(BigDecimal distance,
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
                    "weights must sum to exactly 1.0 but summed to " + sum.stripTrailingZeros().toPlainString());
        }
        return new MatchingWeights(
                distance.doubleValue(),
                availability.doubleValue(),
                rating.doubleValue(),
                skill.doubleValue(),
                performance.doubleValue());
    }

    /**
     * Convenience factory taking {@code double} inputs. Each value is converted to
     * {@link BigDecimal} via its exact string form so, for example, {@code 0.1} is treated as the
     * decimal 0.1 rather than its binary expansion. Delegates to {@link #ofExact}.
     */
    public static MatchingWeights of(double distance,
                                     double availability,
                                     double rating,
                                     double skill,
                                     double performance) {
        return ofExact(
                bd(distance),
                bd(availability),
                bd(rating),
                bd(skill),
                bd(performance));
    }

    private static BigDecimal bd(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            // Let require(...) produce the range error with a stable message.
            return BigDecimal.valueOf(-1);
        }
        return new BigDecimal(Double.toString(value));
    }

    private static void require(String name, BigDecimal value) {
        Objects.requireNonNull(value, () -> name + " must not be null");
        if (value.compareTo(BigDecimal.ZERO) < 0 || value.compareTo(ONE) > 0) {
            throw new WeightValidationException(
                    name + " must be in [0.0, 1.0] but was " + value.stripTrailingZeros().toPlainString());
        }
    }

    /**
     * Computes the matching score for a set of component scores as the weighted sum
     * (Requirement 8.3, Property 18):
     * {@code distance×w1 + availability×w2 + rating×w3 + skill×w4 + performance×w5}.
     */
    public double score(ScoreComponents components) {
        Objects.requireNonNull(components, "components must not be null");
        return components.distanceScore() * distanceWeight
                + components.availabilityScore() * availabilityWeight
                + components.ratingScore() * ratingWeight
                + components.skillScore() * skillWeight
                + components.performanceScore() * performanceWeight;
    }

    /** Rounds a raw score to a stable number of decimals for display/audit; not used in ranking. */
    public static double round(double rawScore, int decimals) {
        return BigDecimal.valueOf(rawScore).setScale(decimals, RoundingMode.HALF_UP).doubleValue();
    }
}
