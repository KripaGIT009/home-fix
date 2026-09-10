package com.homefix.customer.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A customer data-deletion request (Requirement 26.8, 26.9).
 *
 * <p>Lifecycle: {@code ACKNOWLEDGED} on receipt (the acknowledgment happens synchronously,
 * comfortably within the 24-hour SLA of Requirement 26.8). A scheduled sweep later
 * anonymizes the customer's PII once {@code anonymizeAfter} elapses (within 30 days per
 * Requirement 26.9), moving the request to {@code ANONYMIZED}.
 */
@Entity
@Table(name = "deletion_request")
public class DeletionRequest {

    public enum Status {
        ACKNOWLEDGED,
        ANONYMIZED
    }

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private Status status;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private Instant requestedAt;

    @Column(name = "acknowledged_at", nullable = false)
    private Instant acknowledgedAt;

    /** Deadline by which PII must be anonymized (Requirement 26.9). */
    @Column(name = "anonymize_after", nullable = false)
    private Instant anonymizeAfter;

    @Column(name = "anonymized_at")
    private Instant anonymizedAt;

    protected DeletionRequest() {
        // JPA
    }

    private DeletionRequest(UUID id, UUID customerId, Instant acknowledgedAt, Instant anonymizeAfter) {
        this.id = id;
        this.customerId = customerId;
        this.status = Status.ACKNOWLEDGED;
        this.requestedAt = acknowledgedAt;
        this.acknowledgedAt = acknowledgedAt;
        this.anonymizeAfter = anonymizeAfter;
    }

    /**
     * Creates an acknowledged deletion request. Acknowledgment is immediate, satisfying the
     * 24-hour SLA (Requirement 26.8); {@code anonymizeAfter} sets the 30-day deadline
     * (Requirement 26.9).
     */
    public static DeletionRequest acknowledge(UUID customerId, Instant now, Instant anonymizeAfter) {
        return new DeletionRequest(UUID.randomUUID(), customerId, now, anonymizeAfter);
    }

    public void markAnonymized(Instant when) {
        this.status = Status.ANONYMIZED;
        this.anonymizedAt = when;
    }

    public UUID getId() {
        return id;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public Status getStatus() {
        return status;
    }

    public Instant getRequestedAt() {
        return requestedAt;
    }

    public Instant getAcknowledgedAt() {
        return acknowledgedAt;
    }

    public Instant getAnonymizeAfter() {
        return anonymizeAfter;
    }

    public Instant getAnonymizedAt() {
        return anonymizedAt;
    }
}
