package com.homefix.rating.audit;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * An Audit_Log entry recording an Admin moderation action (Requirement 15.9). Each removal captures
 * the acting Admin's user ID and the removal reason so the moderation trail is attributable.
 */
@Entity
@Table(name = "audit_log")
public class AuditLogEntry {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "entity_type", nullable = false, updatable = false, length = 32)
    private String entityType;

    @Column(name = "entity_id", nullable = false, updatable = false)
    private UUID entityId;

    @Column(name = "action", nullable = false, updatable = false, length = 64)
    private String action;

    /** The Admin who performed the action (Requirement 15.9). */
    @Column(name = "actor_id", nullable = false, updatable = false)
    private UUID actorId;

    @Column(name = "reason", length = 1000, updatable = false)
    private String reason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AuditLogEntry() {
        // JPA
    }

    private AuditLogEntry(String entityType, UUID entityId, String action, UUID actorId,
                          String reason, Instant createdAt) {
        this.id = UUID.randomUUID();
        this.entityType = entityType;
        this.entityId = entityId;
        this.action = action;
        this.actorId = actorId;
        this.reason = reason;
        this.createdAt = createdAt;
    }

    public static AuditLogEntry reviewRemoval(UUID reviewId, UUID actorId, String reason, Instant at) {
        return new AuditLogEntry("REVIEW", reviewId, "REVIEW_REMOVED", actorId, reason, at);
    }

    public UUID getId() {
        return id;
    }

    public String getEntityType() {
        return entityType;
    }

    public UUID getEntityId() {
        return entityId;
    }

    public String getAction() {
        return action;
    }

    public UUID getActorId() {
        return actorId;
    }

    public String getReason() {
        return reason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
