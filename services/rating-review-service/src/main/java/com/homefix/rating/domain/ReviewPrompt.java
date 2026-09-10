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
 * An open invitation to submit a review, created when a booking's payment completes
 * (Requirement 15.1, 15.10). One prompt is opened for the customer (to review the provider) and one
 * for the provider (to review the customer), each valid for 7 calendar days from the
 * PAYMENT_COMPLETED timestamp.
 *
 * <p>A submission is accepted if and only if the current time is before {@link #expiresAt}
 * (Requirement 15.2, Property 15). {@link #fulfilled} guards against a second submission against the
 * same prompt.
 */
@Entity
@Table(name = "review_prompt")
public class ReviewPrompt {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "booking_id", nullable = false, updatable = false)
    private UUID bookingId;

    /** Payment that opened this prompt; used to short-circuit duplicate PaymentCompleted deliveries. */
    @Column(name = "payment_id", nullable = false, updatable = false)
    private UUID paymentId;

    /** The party expected to author the review. */
    @Enumerated(EnumType.STRING)
    @Column(name = "reviewer_role", nullable = false, updatable = false, length = 16)
    private ReviewerRole reviewerRole;

    @Column(name = "reviewer_id", nullable = false, updatable = false)
    private UUID reviewerId;

    @Column(name = "reviewee_id", nullable = false, updatable = false)
    private UUID revieweeId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** PAYMENT_COMPLETED timestamp + review window (Requirement 15.2, Property 15). */
    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "fulfilled", nullable = false)
    private boolean fulfilled;

    protected ReviewPrompt() {
        // JPA
    }

    private ReviewPrompt(UUID bookingId, UUID paymentId, ReviewerRole reviewerRole, UUID reviewerId,
                         UUID revieweeId, Instant createdAt, Instant expiresAt) {
        this.id = UUID.randomUUID();
        this.bookingId = bookingId;
        this.paymentId = paymentId;
        this.reviewerRole = reviewerRole;
        this.reviewerId = reviewerId;
        this.revieweeId = revieweeId;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.fulfilled = false;
    }

    public static ReviewPrompt open(UUID bookingId, UUID paymentId, ReviewerRole reviewerRole,
                                    UUID reviewerId, UUID revieweeId, Instant createdAt,
                                    Instant expiresAt) {
        return new ReviewPrompt(bookingId, paymentId, reviewerRole, reviewerId, revieweeId,
                createdAt, expiresAt);
    }

    /**
     * Whether a submission at {@code now} falls within the open window (Requirement 15.2,
     * Property 15). The window is open on {@code [createdAt, expiresAt)}; a submission exactly at
     * or after expiry is rejected.
     */
    public boolean isOpenAt(Instant now) {
        return now.isBefore(expiresAt);
    }

    public void markFulfilled() {
        this.fulfilled = true;
    }

    public UUID getId() {
        return id;
    }

    public UUID getBookingId() {
        return bookingId;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public ReviewerRole getReviewerRole() {
        return reviewerRole;
    }

    public UUID getReviewerId() {
        return reviewerId;
    }

    public UUID getRevieweeId() {
        return revieweeId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public boolean isFulfilled() {
        return fulfilled;
    }
}
