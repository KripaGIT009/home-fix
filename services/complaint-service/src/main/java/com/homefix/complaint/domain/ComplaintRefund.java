package com.homefix.complaint.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

/**
 * The audit record of a Support_Agent-approved refund on a complaint (Requirement 16.5, 16.6).
 *
 * <p>A complaint carries <strong>at most one</strong> refund: {@code complaint_id} is unique, so a
 * repeated or concurrent approval cannot create a second record and therefore cannot reach the
 * Payment Service a second time. The record is created {@link ComplaintRefundStatus#PENDING} and
 * committed before the Payment Service is called; the outcome then moves it to
 * {@link ComplaintRefundStatus#SUCCEEDED} (with the Payment Service reference) or
 * {@link ComplaintRefundStatus#FAILED} (with the reason). A failed refund is handed to
 * Finance_Admin for manual processing (16.6) and is not retried through the API.
 *
 * <p>{@link #paymentIdempotencyKey()} is derived from the record id, so every call the complaint
 * makes to the Payment Service for this refund carries the same key and the Payment Service can
 * de-duplicate it on its side too.
 */
@Entity
@Table(name = "complaint_refund",
        uniqueConstraints = @UniqueConstraint(name = "uk_complaint_refund_complaint",
                columnNames = "complaint_id"))
public class ComplaintRefund {

    /** Maximum length of the agent's refund reason and of a recorded failure reason. */
    public static final int REASON_MAX_LENGTH = 500;

    /** Maximum length of a client idempotency key; matches the Payment Service refund key limit. */
    public static final int CLIENT_KEY_MAX_LENGTH = 64;

    private static final String PAYMENT_KEY_PREFIX = "complaint-refund:";

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** Unique: one refund per complaint is the authoritative guard against repeated refunds. */
    @Column(name = "complaint_id", nullable = false, updatable = false)
    private UUID complaintId;

    @Column(name = "booking_id", nullable = false, updatable = false)
    private UUID bookingId;

    @Column(name = "amount", nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal amount;

    /** The agent's justification for the refund; optional. */
    @Column(name = "reason", length = REASON_MAX_LENGTH, updatable = false)
    private String reason;

    /** The Support_Agent (JWT subject) who approved the refund. */
    @Column(name = "approved_by", nullable = false, updatable = false)
    private UUID approvedBy;

    /** Client-supplied key letting a retried request replay this refund's outcome; optional. */
    @Column(name = "client_idempotency_key", length = CLIENT_KEY_MAX_LENGTH, updatable = false)
    private String clientIdempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ComplaintRefundStatus status;

    /** The Payment Service refund reference once the refund succeeded. */
    @Column(name = "external_reference", length = 128)
    private String externalReference;

    @Column(name = "failure_reason", length = REASON_MAX_LENGTH)
    private String failureReason;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private Instant requestedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Version
    @Column(name = "version")
    private Long version;

    protected ComplaintRefund() {
        // JPA
    }

    private ComplaintRefund(UUID complaintId, UUID bookingId, BigDecimal amount, String reason,
                            UUID approvedBy, String clientIdempotencyKey, Instant requestedAt) {
        this.id = UUID.randomUUID();
        this.complaintId = complaintId;
        this.bookingId = bookingId;
        this.amount = amount;
        this.reason = reason;
        this.approvedBy = approvedBy;
        this.clientIdempotencyKey = clientIdempotencyKey;
        this.status = ComplaintRefundStatus.PENDING;
        this.requestedAt = requestedAt;
    }

    /** Creates a PENDING refund record; persist and commit it before calling the Payment Service. */
    public static ComplaintRefund reserve(UUID complaintId, UUID bookingId, BigDecimal amount,
                                          String reason, UUID approvedBy,
                                          String clientIdempotencyKey, Instant requestedAt) {
        return new ComplaintRefund(complaintId, bookingId, amount, reason, approvedBy,
                clientIdempotencyKey, requestedAt);
    }

    /** Records that the Payment Service executed the refund (Requirement 16.5). */
    public void markSucceeded(String externalReference, Instant completedAt) {
        requirePending(ComplaintRefundStatus.SUCCEEDED);
        this.status = ComplaintRefundStatus.SUCCEEDED;
        this.externalReference = externalReference;
        this.completedAt = completedAt;
    }

    /** Records that the Payment Service rejected or failed the refund (Requirement 16.6). */
    public void markFailed(String failureReason, Instant completedAt) {
        requirePending(ComplaintRefundStatus.FAILED);
        this.status = ComplaintRefundStatus.FAILED;
        this.failureReason = failureReason != null && failureReason.length() > REASON_MAX_LENGTH
                ? failureReason.substring(0, REASON_MAX_LENGTH)
                : failureReason;
        this.completedAt = completedAt;
    }

    /**
     * The idempotency key sent to the Payment Service for this refund: stable for the life of the
     * record and at most 64 characters, the Payment Service limit.
     */
    public String paymentIdempotencyKey() {
        return PAYMENT_KEY_PREFIX + id;
    }

    private void requirePending(ComplaintRefundStatus target) {
        if (status != ComplaintRefundStatus.PENDING) {
            throw new IllegalStateException(
                    "Refund " + id + " cannot move from " + status + " to " + target);
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getComplaintId() {
        return complaintId;
    }

    public UUID getBookingId() {
        return bookingId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getReason() {
        return reason;
    }

    public UUID getApprovedBy() {
        return approvedBy;
    }

    public String getClientIdempotencyKey() {
        return clientIdempotencyKey;
    }

    public ComplaintRefundStatus getStatus() {
        return status;
    }

    public String getExternalReference() {
        return externalReference;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public Instant getRequestedAt() {
        return requestedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }
}
