package com.homefix.rating.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence for {@link Review}. Beyond CRUD, exposes the queries backing aggregate recalculation
 * (Requirement 15.4, Property 16), same-IP fraud detection (Requirement 15.5a) and the Admin
 * Portal moderation list (Requirement 19.2).
 */
public interface ReviewRepository extends JpaRepository<Review, UUID> {

    /** All customer-to-provider reviews for a provider, used to recompute the weighted aggregate. */
    List<Review> findByRevieweeIdAndReviewerRole(UUID revieweeId, ReviewerRole reviewerRole);

    /** Reviews from a given IP submitted on/after {@code since}; backs same-IP burst detection. */
    List<Review> findBySourceIpAndSubmittedAtGreaterThanEqual(String sourceIp, Instant since);

    /** True if a review already exists for the booking from this reviewer (one review per prompt). */
    boolean existsByBookingIdAndReviewerId(UUID bookingId, UUID reviewerId);

    // ---- Admin Portal moderation list (Requirement 19.2) -----------------------------------------
    // One query per derived ModerationStatus, newest first, bounded by the caller's page.

    /** Every review, newest first. */
    List<Review> findAllByOrderBySubmittedAtDesc(Pageable page);

    /** FLAGGED: active reviews held by fraud detection (15.5). */
    List<Review> findByActiveTrueAndFlaggedTrueOrderBySubmittedAtDesc(Pageable page);

    /** PUBLISHED: active, unflagged reviews counted in the aggregate. */
    List<Review> findByActiveTrueAndFlaggedFalseOrderBySubmittedAtDesc(Pageable page);

    /** REMOVED: reviews an Admin deactivated for a policy violation (15.9). */
    List<Review> findByActiveFalseOrderBySubmittedAtDesc(Pageable page);
}
