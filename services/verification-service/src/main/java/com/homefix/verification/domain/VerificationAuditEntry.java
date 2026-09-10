package com.homefix.verification.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * An immutable audit-trail entry recording a single verification status change
 * (Requirement 5.11): the actor's user ID, timestamp, previous state, new state, and reason.
 *
 * <p>Entries carry a per-verification, monotonically increasing {@code sequence} number so
 * the trail forms a <em>contiguous chain</em>: entry {@code n}'s {@code fromState} always
 * equals entry {@code n-1}'s {@code toState}. The first entry (sequence 0) records the
 * initial transition out of the seed state.
 */
@Entity
@Table(name = "verification_audit_entry")
public class VerificationAuditEntry {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne
    @JoinColumn(name = "verification_id", nullable = false)
    private Verification verification;

    /** Zero-based position of this entry within the verification's audit chain. */
    @Column(name = "sequence", nullable = false, updatable = false)
    private long sequence;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_state", nullable = false, length = 40, updatable = false)
    private VerificationStatus fromState;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_state", nullable = false, length = 40, updatable = false)
    private VerificationStatus toState;

    /** The user ID of the actor who performed the transition (Admin, provider, or system). */
    @Column(name = "actor_id", nullable = false, updatable = false)
    private UUID actorId;

    @Column(name = "reason", length = 1000, updatable = false)
    private String reason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected VerificationAuditEntry() {
        // JPA
    }

    VerificationAuditEntry(Verification verification, long sequence,
                           VerificationStatus fromState, VerificationStatus toState,
                           UUID actorId, String reason) {
        this.id = UUID.randomUUID();
        this.verification = verification;
        this.sequence = sequence;
        this.fromState = fromState;
        this.toState = toState;
        this.actorId = actorId;
        this.reason = reason;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public long getSequence() {
        return sequence;
    }

    public VerificationStatus getFromState() {
        return fromState;
    }

    public VerificationStatus getToState() {
        return toState;
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
