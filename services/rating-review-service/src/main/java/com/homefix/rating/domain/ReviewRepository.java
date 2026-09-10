package com.homefix.rating.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence for {@link Review}. Beyond CRUD, exposes the queries backing aggregate recalculation
 * (Requirement 15.4, Property 16) and same-IP fraud detection (Requirement 15.5a).
 */
public interface ReviewRepository extends JpaRepository<Review, UUID> {

    /** All customer-to-provider reviews for a provider, used to recompute the weighted aggregate. */
    List<Review> findByRevieweeIdAndReviewerRole(UUID revieweeId, ReviewerRole reviewerRole);

    /** Reviews from a given IP submitted on/after {@code since}; backs same-IP burst detection. */
    List<Review> findBySourceIpAndSubmittedAtGreaterThanEqual(String sourceIp, Instant since);

    /** True if a review already exists for the booking from this reviewer (one review per prompt). */
    boolean existsByBookingIdAndReviewerId(UUID bookingId, UUID reviewerId);
}
