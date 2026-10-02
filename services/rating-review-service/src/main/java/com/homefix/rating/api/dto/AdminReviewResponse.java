package com.homefix.rating.api.dto;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.homefix.rating.domain.ModerationStatus;
import com.homefix.rating.domain.Review;

/**
 * A review as the Admin Portal's moderation table reads it ({@code AdminReview} in
 * {@code features/reviews/api.ts}, Requirement 19.2).
 *
 * <p>{@code bookingReference}, {@code reviewerName} and {@code providerName} are owned by the
 * Booking, Auth and Provider services; this service holds only their ids, so they are sent as
 * {@code null} rather than calling across services or inventing a value. {@code status} is the
 * {@link ModerationStatus} derived from the active/flagged columns. {@code flagReason} is optional
 * in the portal and fraud-detection reasons are logged, not stored, so it is always omitted.
 */
public record AdminReviewResponse(
        UUID id,
        String bookingReference,
        String reviewerName,
        String providerName,
        int rating,
        String comment,
        String status,
        @JsonInclude(JsonInclude.Include.NON_NULL) String flagReason,
        Instant createdAt) {

    public static AdminReviewResponse from(Review review) {
        return new AdminReviewResponse(
                review.getId(),
                null,
                null,
                null,
                review.getOverallRating(),
                review.getReviewText(),
                ModerationStatus.of(review).name(),
                null,
                review.getSubmittedAt());
    }
}
