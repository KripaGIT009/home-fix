package com.homefix.rating.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A submitted review (Requirement 15). Customer-to-provider reviews carry integer star ratings on
 * five dimensions (overall, behavior, quality, timeliness, pricing_transparency); provider-to-
 * customer reviews use only {@link #overallRating}.
 *
 * <p>{@link #flagged} reviews are held pending Admin moderation and are excluded from a provider's
 * aggregate until approved (Requirement 15.5, Property 17). {@link #active} is cleared when an Admin
 * removes a review for a policy violation (Requirement 15.9). Only reviews that are both
 * {@code active} and not {@code flagged} contribute to the aggregate.
 */
@Entity
@Table(name = "review")
public class Review {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "booking_id", nullable = false, updatable = false)
    private UUID bookingId;

    /** Author of the review. */
    @Column(name = "reviewer_id", nullable = false, updatable = false)
    private UUID reviewerId;

    /** Subject of the review (the provider for customer reviews; the customer for provider reviews). */
    @Column(name = "reviewee_id", nullable = false, updatable = false)
    private UUID revieweeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "reviewer_role", nullable = false, updatable = false, length = 16)
    private ReviewerRole reviewerRole;

    @Column(name = "overall_rating", nullable = false, updatable = false)
    private int overallRating;

    @Column(name = "behavior_rating", updatable = false)
    private Integer behaviorRating;

    @Column(name = "quality_rating", updatable = false)
    private Integer qualityRating;

    @Column(name = "timeliness_rating", updatable = false)
    private Integer timelinessRating;

    @Column(name = "pricing_transparency_rating", updatable = false)
    private Integer pricingTransparencyRating;

    @Column(name = "review_text", length = 1000, updatable = false)
    private String reviewText;

    /** IP address the submission originated from; used by same-IP burst detection (Requirement 15.5a). */
    @Column(name = "source_ip", length = 45, updatable = false)
    private String sourceIp;

    /** True while held for Admin moderation; excluded from the aggregate (Requirement 15.5, Property 17). */
    @Column(name = "is_flagged", nullable = false)
    private boolean flagged;

    /** Cleared when an Admin removes the review for a policy violation (Requirement 15.9). */
    @Column(name = "is_active", nullable = false)
    private boolean active;

    @Column(name = "submitted_at", nullable = false, updatable = false)
    private Instant submittedAt;

    protected Review() {
        // JPA
    }

    private Review(UUID bookingId, UUID reviewerId, UUID revieweeId, ReviewerRole reviewerRole,
                   int overallRating, Integer behaviorRating, Integer qualityRating,
                   Integer timelinessRating, Integer pricingTransparencyRating, String reviewText,
                   String sourceIp, boolean flagged, Instant submittedAt) {
        this.id = UUID.randomUUID();
        this.bookingId = bookingId;
        this.reviewerId = reviewerId;
        this.revieweeId = revieweeId;
        this.reviewerRole = reviewerRole;
        this.overallRating = overallRating;
        this.behaviorRating = behaviorRating;
        this.qualityRating = qualityRating;
        this.timelinessRating = timelinessRating;
        this.pricingTransparencyRating = pricingTransparencyRating;
        this.reviewText = reviewText;
        this.sourceIp = sourceIp;
        this.flagged = flagged;
        this.active = true;
        this.submittedAt = submittedAt;
    }

    /** Factory for a customer-to-provider review with all five dimensions. */
    public static Review customerReview(UUID bookingId, UUID customerId, UUID providerId,
                                        int overall, int behavior, int quality, int timeliness,
                                        int pricingTransparency, String reviewText, String sourceIp,
                                        boolean flagged, Instant submittedAt) {
        return new Review(bookingId, customerId, providerId, ReviewerRole.CUSTOMER,
                overall, behavior, quality, timeliness, pricingTransparency, reviewText, sourceIp,
                flagged, submittedAt);
    }

    /** Factory for a provider-to-customer review using only the overall dimension. */
    public static Review providerReview(UUID bookingId, UUID providerId, UUID customerId,
                                        int overall, String reviewText, String sourceIp,
                                        boolean flagged, Instant submittedAt) {
        return new Review(bookingId, providerId, customerId, ReviewerRole.PROVIDER,
                overall, null, null, null, null, reviewText, sourceIp, flagged, submittedAt);
    }

    /** Admin approval clears the flag so the review re-enters the aggregate (Requirement 15.5). */
    public void approve() {
        this.flagged = false;
    }

    /** Admin removal deactivates the review; recalculation then excludes it (Requirement 15.9). */
    public void deactivate() {
        this.active = false;
    }

    /** Whether this review contributes to the provider's aggregate (active and not flagged). */
    public boolean countsTowardAggregate() {
        return active && !flagged;
    }

    public UUID getId() {
        return id;
    }

    public UUID getBookingId() {
        return bookingId;
    }

    public UUID getReviewerId() {
        return reviewerId;
    }

    public UUID getRevieweeId() {
        return revieweeId;
    }

    public ReviewerRole getReviewerRole() {
        return reviewerRole;
    }

    public int getOverallRating() {
        return overallRating;
    }

    public Integer getBehaviorRating() {
        return behaviorRating;
    }

    public Integer getQualityRating() {
        return qualityRating;
    }

    public Integer getTimelinessRating() {
        return timelinessRating;
    }

    public Integer getPricingTransparencyRating() {
        return pricingTransparencyRating;
    }

    public String getReviewText() {
        return reviewText;
    }

    public String getSourceIp() {
        return sourceIp;
    }

    public boolean isFlagged() {
        return flagged;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }
}
