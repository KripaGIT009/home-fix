package com.homefix.provider.api.dto;

import java.util.List;
import java.util.UUID;

import com.homefix.provider.eligibility.ProviderMatch;

/**
 * Response of {@code GET /internal/providers/eligible}: the eligible providers with their five
 * Matching_Score components, each in {@code [0, 1]} (Requirements 8.2, 8.3).
 *
 * <p>The shape is the Dispatch Engine's contract ({@code HttpProviderQueryAdapter
 * .EligibleProvidersResponse}); field names must not change without changing it there.
 */
public record EligibleProvidersResponse(List<EligibleProvider> providers) {

    public static EligibleProvidersResponse from(List<ProviderMatch> matches) {
        return new EligibleProvidersResponse(matches.stream().map(EligibleProvider::from).toList());
    }

    /** A single eligible provider with its component scores. */
    public record EligibleProvider(
            UUID providerId,
            double distanceScore,
            double availabilityScore,
            double ratingScore,
            double skillScore,
            double performanceScore) {

        static EligibleProvider from(ProviderMatch m) {
            return new EligibleProvider(m.providerId(), m.distanceScore(), m.availabilityScore(),
                    m.ratingScore(), m.skillScore(), m.performanceScore());
        }
    }
}
