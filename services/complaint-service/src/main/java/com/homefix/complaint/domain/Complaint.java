package com.homefix.complaint.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A customer complaint against a completed booking (Requirement 16).
 *
 * <p>Carries the booking reference, the raising customer, the assigned Support_Agent, the category
 * and description, current {@link ComplaintStatus}, and the SLA deadline used by the escalation
 * sweep (16.4). {@link #settlementHeld} tracks whether a provider settlement hold is currently in
 * place for this complaint (16.7, 16.8). {@link #acknowledged} records that the customer was
 * acknowledged within 30 minutes of submission (16.2).
 */
@Entity
@Table(name = "complaint")
public class Complaint {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "booking_id", nullable = false, updatable = false)
    private UUID bookingId;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    /** The provider whose settlement may be held while the complaint is DISPUTED (16.7). */
    @Column(name = "provider_id", updatable = false)
    private UUID providerId;

    /** The Support_Agent the complaint is assigned to (16.1); reassigned on escalation (16.4). */
    @Column(name = "agent_id")
    private UUID agentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, updatable = false, length = 32)
    private ComplaintCategory category;

    @Enumerated(EnumType.STRING)
    @Column(name = "priority", nullable = false, updatable = false, length = 16)
    private ServicePriority priority;

    @Column(name = "description", nullable = false, length = 2000, updatable = false)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private ComplaintStatus status;

    /** True once the customer has been acknowledged within the 30-minute SLA (16.2). */
    @Column(name = "acknowledged", nullable = false)
    private boolean acknowledged;

    /** True while a provider settlement hold is active for this complaint (16.7, 16.8). */
    @Column(name = "settlement_held", nullable = false)
    private boolean settlementHeld;

    /** True once the complaint has been escalated to a Senior_Support_Agent (16.4). */
    @Column(name = "escalated", nullable = false)
    private boolean escalated;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "sla_deadline", nullable = false)
    private Instant slaDeadline;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    protected Complaint() {
        // JPA
    }

    private Complaint(UUID bookingId, UUID customerId, UUID providerId, UUID agentId,
                      ComplaintCategory category, ServicePriority priority, String description,
                      Instant createdAt, Instant slaDeadline) {
        this.id = UUID.randomUUID();
        this.bookingId = bookingId;
        this.customerId = customerId;
        this.providerId = providerId;
        this.agentId = agentId;
        this.category = category;
        this.priority = priority;
        this.description = description;
        this.status = ComplaintStatus.OPEN;
        this.acknowledged = false;
        this.settlementHeld = false;
        this.escalated = false;
        this.createdAt = createdAt;
        this.slaDeadline = slaDeadline;
    }

    /**
     * Creates an OPEN complaint assigned to {@code agentId} with its resolution SLA deadline
     * precomputed from the submission time (Requirement 16.1, 16.4).
     */
    public static Complaint open(UUID bookingId, UUID customerId, UUID providerId, UUID agentId,
                                 ComplaintCategory category, ServicePriority priority,
                                 String description, Instant createdAt, Instant slaDeadline) {
        return new Complaint(bookingId, customerId, providerId, agentId, category, priority,
                description, createdAt, slaDeadline);
    }

    /** Marks the customer as acknowledged (Requirement 16.2). */
    public void markAcknowledged() {
        this.acknowledged = true;
    }

    /** Transitions the complaint to a new status (Requirement 16.3). */
    public void changeStatus(ComplaintStatus newStatus) {
        this.status = newStatus;
    }

    /**
     * Escalates the complaint to a Senior_Support_Agent after an SLA breach (Requirement 16.4).
     * Idempotent: a complaint already escalated is unaffected.
     */
    public void escalateTo(UUID seniorAgentId) {
        this.escalated = true;
        this.agentId = seniorAgentId;
        this.status = ComplaintStatus.ESCALATED;
    }

    /** Places a provider settlement hold and moves to DISPUTED (Requirement 16.7). */
    public void placeSettlementHold() {
        this.settlementHeld = true;
        this.status = ComplaintStatus.DISPUTED;
    }

    /** Releases any active provider settlement hold (Requirement 16.8). */
    public void releaseSettlementHold() {
        this.settlementHeld = false;
    }

    /** Records that the associated refund request was rejected (Requirement 16.6). */
    public void markRefundFailed() {
        this.status = ComplaintStatus.REFUND_FAILED;
    }

    /** Resolves the complaint and stamps the resolution time (Requirement 16.8, 16.9). */
    public void resolve(Instant resolvedAt) {
        this.status = ComplaintStatus.RESOLVED;
        this.resolvedAt = resolvedAt;
    }

    /** Closes the complaint and stamps the closure time (Requirement 16.8, 16.9). */
    public void close(Instant closedAt) {
        this.status = ComplaintStatus.CLOSED;
        this.resolvedAt = closedAt;
    }

    /** Whether the resolution SLA has been breached at {@code now} for a still-open complaint. */
    public boolean isSlaBreachedAt(Instant now) {
        return !status.isTerminal() && !escalated && now.isAfter(slaDeadline);
    }

    public UUID getId() {
        return id;
    }

    public UUID getBookingId() {
        return bookingId;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public UUID getProviderId() {
        return providerId;
    }

    public UUID getAgentId() {
        return agentId;
    }

    public ComplaintCategory getCategory() {
        return category;
    }

    public ServicePriority getPriority() {
        return priority;
    }

    public String getDescription() {
        return description;
    }

    public ComplaintStatus getStatus() {
        return status;
    }

    public boolean isAcknowledged() {
        return acknowledged;
    }

    public boolean isSettlementHeld() {
        return settlementHeld;
    }

    public boolean isEscalated() {
        return escalated;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getSlaDeadline() {
        return slaDeadline;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }
}
