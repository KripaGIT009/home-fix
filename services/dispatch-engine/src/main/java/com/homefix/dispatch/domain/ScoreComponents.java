package com.homefix.dispatch.domain;

/**
 * The five normalised scoring components for a single provider relative to a booking
 * (Requirement 8.3). Each component is expected to be in the range [0.0, 1.0]; higher is
 * better. The Dispatch Engine combines them with {@link MatchingWeights} to produce a single
 * {@code Matching_Score}.
 *
 * <ul>
 *   <li>{@code distanceScore} — closeness of the provider to the customer (1.0 = at the door).</li>
 *   <li>{@code availabilityScore} — how available the provider is right now.</li>
 *   <li>{@code ratingScore} — the provider's aggregate rating, normalised.</li>
 *   <li>{@code skillScore} — how well the provider's skill tags match the requested subcategory.</li>
 *   <li>{@code performanceScore} — historical job performance (acceptance/completion rate).</li>
 * </ul>
 *
 * <p>We use {@code double} consistently for scoring; the components are unitless ratios where a
 * few ULPs of floating-point error are irrelevant to ranking. (BigDecimal is reserved for money
 * in the Pricing Engine.)
 */
public record ScoreComponents(
        double distanceScore,
        double availabilityScore,
        double ratingScore,
        double skillScore,
        double performanceScore) {

    public ScoreComponents {
        requireUnitInterval("distanceScore", distanceScore);
        requireUnitInterval("availabilityScore", availabilityScore);
        requireUnitInterval("ratingScore", ratingScore);
        requireUnitInterval("skillScore", skillScore);
        requireUnitInterval("performanceScore", performanceScore);
    }

    private static void requireUnitInterval(String name, double value) {
        if (Double.isNaN(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(
                    name + " must be in [0.0, 1.0] but was " + value);
        }
    }
}
