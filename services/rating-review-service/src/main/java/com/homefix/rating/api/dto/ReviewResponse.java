package com.homefix.rating.api.dto;

import java.time.Instant;
import java.util.UUID;

import com.homefix.rating.domain.Review;

/**
 * Response body describing a persisted review. {@code flagged} tells the client the review is held
 * for moderation and not yet reflected in the provider's aggregate (Requirement 15.5).
 */
public record ReviewResponse(
        UUID reviewId,
        UUID bookingId,
        UUID revieweeId,
        String reviewerRole,
        int overall,
        boolean flagged,
        Instant submittedAt) {

    public static ReviewResponse from(Review review) {
        return new ReviewResponse(
                review.getId(),
                review.getBookingId(),
                review.getRevieweeId(),
                review.getReviewerRole().name(),
                review.getOverallRating(),
                review.isFlagged(),
                review.getSubmittedAt());
    }
}
