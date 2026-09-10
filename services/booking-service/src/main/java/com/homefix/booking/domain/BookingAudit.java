package com.homefix.booking.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/**
 * Immutable audit record for a single applied booking state transition (design
 * {@code BOOKING_AUDIT}, Requirement 9.15, Property 9).
 *
 * <p>Exactly one row is written per successfully applied transition. The tuple recorded is
 * {@code (bookingId, from_state, to_state, actor_id, actor_role, timestamp, reason)}. For a
 * booking's initial creation the {@code from_state} is null (the chain begins at CREATED).
 */
@Entity
@Table(name = "booking_audit", indexes = {
        @Index(name = "idx_booking_audit_booking", columnList = "booking_id, transitioned_at")
})
public class BookingAudit {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "booking_id", nullable = false, updatable = false)
    private UUID bookingId;

    /** Source state; null only for the initial CREATED entry that opens the chain. */
    @Enumerated(EnumType.STRING)
    @Column(name = "from_state", length = 40)
    private BookingStatus fromState;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_state", nullable = false, length = 40)
    private BookingStatus toState;

    /** Actor user id for human actors; null when the transition is system-initiated. */
    @Column(name = "actor_id")
    private UUID actorId;

    /** Actor role for human actors, or the service name for system-initiated transitions. */
    @Column(name = "actor_role", nullable = false, length = 60)
    private String actorRole;

    @Column(name = "transitioned_at", nullable = false, updatable = false)
    private Instant transitionedAt;

    @Column(name = "reason", length = 500)
    private String reason;

    protected BookingAudit() {
        // JPA
    }

    private BookingAudit(UUID bookingId, BookingStatus fromState, BookingStatus toState,
                         UUID actorId, String actorRole, Instant transitionedAt, String reason) {
        this.id = UUID.randomUUID();
        this.bookingId = bookingId;
        this.fromState = fromState;
        this.toState = toState;
        this.actorId = actorId;
        this.actorRole = actorRole;
        this.transitionedAt = transitionedAt;
        this.reason = reason;
    }

    public static BookingAudit of(UUID bookingId, BookingStatus fromState, BookingStatus toState,
                                  UUID actorId, String actorRole, Instant transitionedAt, String reason) {
        return new BookingAudit(bookingId, fromState, toState, actorId, actorRole, transitionedAt, reason);
    }

    public UUID getId() {
        return id;
    }

    public UUID getBookingId() {
        return bookingId;
    }

    public BookingStatus getFromState() {
        return fromState;
    }

    public BookingStatus getToState() {
        return toState;
    }

    public UUID getActorId() {
        return actorId;
    }

    public String getActorRole() {
        return actorRole;
    }

    public Instant getTransitionedAt() {
        return transitionedAt;
    }

    public String getReason() {
        return reason;
    }
}
