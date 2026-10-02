package com.homefix.provider.eligibility;

import java.util.UUID;

/**
 * An eligible provider and the five Matching_Score components the Dispatch Engine weighs
 * (Requirement 8.3). Every component is in {@code [0.0, 1.0]}, higher is better — the Dispatch
 * Engine's {@code ScoreComponents} rejects anything outside that interval.
 *
 * @param distanceKm haversine distance from the customer; not part of the wire contract, kept for
 *                   ordering and logging
 */
public record ProviderMatch(
        UUID providerId,
        double distanceKm,
        double distanceScore,
        double availabilityScore,
        double ratingScore,
        double skillScore,
        double performanceScore) {
}
