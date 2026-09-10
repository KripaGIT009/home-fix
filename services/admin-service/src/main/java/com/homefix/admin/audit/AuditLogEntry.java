package com.homefix.admin.audit;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Immutable Audit_Log record for a single Admin action (Requirement 19.8).
 *
 * <p>Maps to the {@code audit_log} table described in the design ERD. The store is append-only
 * and tamper-evident (Requirement 26.10); this entity exposes no setters and no update/delete
 * repository operations are provided, so once written a row is never mutated in code.
 *
 * <p>The {@code beforeValues}/{@code afterValues} field maps are serialised to JSON text. For
 * CREATE actions only {@code afterValues} is populated; for DELETE actions only
 * {@code beforeValues}; for UPDATE/APPROVE/REJECT both are populated so the change is fully
 * reconstructable.
 */
@Entity
@Table(name = "audit_log")
public class AuditLogEntry {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "actor_id", nullable = false, updatable = false)
    private UUID actorId;

    @Column(name = "action_type", nullable = false, updatable = false, length = 32)
    @Enumerated(EnumType.STRING)
    private AdminAction actionType;

    @Column(name = "entity_type", nullable = false, updatable = false, length = 128)
    private String entityType;

    @Column(name = "entity_id", updatable = false)
    private String entityId;

    @Column(name = "before_values", updatable = false, columnDefinition = "text")
    private String beforeValues;

    @Column(name = "after_values", updatable = false, columnDefinition = "text")
    private String afterValues;

    @Column(name = "logged_at", nullable = false, updatable = false)
    private Instant loggedAt;

    protected AuditLogEntry() {
        // JPA
    }

    public AuditLogEntry(UUID id,
                         UUID actorId,
                         AdminAction actionType,
                         String entityType,
                         String entityId,
                         String beforeValues,
                         String afterValues,
                         Instant loggedAt) {
        this.id = id;
        this.actorId = actorId;
        this.actionType = actionType;
        this.entityType = entityType;
        this.entityId = entityId;
        this.beforeValues = beforeValues;
        this.afterValues = afterValues;
        this.loggedAt = loggedAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getActorId() {
        return actorId;
    }

    public AdminAction getActionType() {
        return actionType;
    }

    public String getEntityType() {
        return entityType;
    }

    public String getEntityId() {
        return entityId;
    }

    public String getBeforeValues() {
        return beforeValues;
    }

    public String getAfterValues() {
        return afterValues;
    }

    public Instant getLoggedAt() {
        return loggedAt;
    }
}
