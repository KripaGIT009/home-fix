package com.homefix.rating.service;

import java.time.Instant;
import java.util.List;

/**
 * The inputs the {@link FraudDetector} needs to evaluate a single submission (Requirement 15.5,
 * Property 17). Assembled by {@code ReviewService} from the repository and the reviewer-account
 * port so the detector itself stays pure and directly unit-testable.
 *
 * @param sourceIp            IP the submission came from (may be {@code null} if unavailable)
 * @param submittedAt         submission timestamp
 * @param recentSameIpCount   number of prior reviews from {@code sourceIp} inside the burst window,
 *                            including any about to be written for other bookings
 * @param overallRating       the submitted overall star rating (1–5)
 * @param providerHistory     the provider's prior contributing overall ratings, for the SD check
 * @param reviewerCreatedAt   when the reviewer's account was created (may be {@code null})
 */
public record FraudSignals(
        String sourceIp,
        Instant submittedAt,
        int recentSameIpCount,
        int overallRating,
        List<Integer> providerHistory,
        Instant reviewerCreatedAt) {
}
