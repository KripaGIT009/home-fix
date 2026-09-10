package com.homefix.verification.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;

import com.homefix.verification.service.VerificationException;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * Verification aggregate for a single provider (Requirement 5).
 *
 * <p>Owns the current {@link VerificationStatus}, the uploaded {@link VerificationDocument}s,
 * the background-check result, and the {@link VerificationAuditEntry} chain. All status
 * changes flow through {@link #transitionTo}, which enforces the state machine
 * (Requirement 5.1, 5.2, Property 24) and appends a contiguous audit entry (Requirement 5.11).
 */
@Entity
@Table(name = "verification")
public class Verification {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** The provider this verification record belongs to (also the aggregate key here). */
    @Column(name = "provider_id", nullable = false, unique = true, updatable = false)
    private UUID providerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 40)
    private VerificationStatus status;

    @Column(name = "background_check_started_at")
    private Instant backgroundCheckStartedAt;

    @Column(name = "background_check_result", length = 2000)
    private String backgroundCheckResult;

    @OneToMany(mappedBy = "verification", cascade = CascadeType.ALL, orphanRemoval = true,
            fetch = FetchType.EAGER)
    private List<VerificationDocument> documents = new ArrayList<>();

    @OneToMany(mappedBy = "verification", cascade = CascadeType.ALL, orphanRemoval = true,
            fetch = FetchType.EAGER)
    @OrderBy("sequence ASC")
    private List<VerificationAuditEntry> auditTrail = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version")
    private long version;

    protected Verification() {
        // JPA
    }

    private Verification(UUID id, UUID providerId) {
        this.id = id;
        this.providerId = providerId;
        this.status = VerificationStatus.PENDING;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    /** Factory for a brand-new verification record starting in {@code PENDING}. */
    public static Verification create(UUID providerId) {
        return new Verification(UUID.randomUUID(), providerId);
    }

    /**
     * Transitions this verification to {@code target}, recording an audit entry with the
     * actor and reason (Requirement 5.11). Rejects any transition not permitted by the state
     * machine with an error that names the current state and the disallowed target
     * (Requirement 5.2, Property 24).
     *
     * @throws VerificationException with {@code errorCode = INVALID_STATE_TRANSITION} and HTTP
     *         409 when the transition is not in the permitted map.
     */
    public VerificationAuditEntry transitionTo(VerificationStatus target, UUID actorId, String reason) {
        VerificationStatus current = this.status;
        if (!VerificationStateMachine.isPermitted(current, target)) {
            throw new VerificationException(
                    HttpStatus.CONFLICT,
                    "INVALID_STATE_TRANSITION",
                    "Transition from " + current + " to " + target + " is not permitted",
                    List.of("currentState=" + current, "disallowedTarget=" + target));
        }
        VerificationAuditEntry entry = new VerificationAuditEntry(
                this, nextSequence(), current, target, actorId, reason);
        this.auditTrail.add(entry);
        this.status = target;
        touch();
        return entry;
    }

    private long nextSequence() {
        return this.auditTrail.size();
    }

    public void addDocument(DocumentType type, String storageRef, String contentType, long sizeBytes) {
        this.documents.add(new VerificationDocument(this, type, storageRef, contentType, sizeBytes));
        touch();
    }

    public void recordBackgroundCheckStarted(Instant startedAt) {
        this.backgroundCheckStartedAt = startedAt;
        touch();
    }

    public void recordBackgroundCheckResult(String result) {
        this.backgroundCheckResult = result;
        touch();
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }

    // ----- Accessors -----

    public UUID getId() {
        return id;
    }

    public UUID getProviderId() {
        return providerId;
    }

    public VerificationStatus getStatus() {
        return status;
    }

    public Instant getBackgroundCheckStartedAt() {
        return backgroundCheckStartedAt;
    }

    public String getBackgroundCheckResult() {
        return backgroundCheckResult;
    }

    public List<VerificationDocument> getDocuments() {
        return documents;
    }

    /** The audit trail ordered oldest-first (sequence ascending). */
    public List<VerificationAuditEntry> getAuditTrail() {
        return auditTrail;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
