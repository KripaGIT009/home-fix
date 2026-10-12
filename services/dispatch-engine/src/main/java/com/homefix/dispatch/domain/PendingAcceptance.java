package com.homefix.dispatch.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A provider acceptance that has been recorded but not yet fully applied: the Booking Service's
 * transition and the {@code ProviderAccepted} outbox row are still outstanding (Requirement 8.6).
 * See {@link com.homefix.dispatch.port.AcceptanceLedger} for the protocol.
 *
 * <p>Keyed by booking: a booking has at most one acceptance in flight, since one dispatch run
 * stops at the first provider who accepts. The row is deleted in the same transaction that writes
 * the event, so a row's presence means "not yet announced".
 *
 * <p>Mirrors {@code V2__pending_acceptance.sql} exactly ({@code ddl-auto=validate}).
 */
@Entity
@Table(name = "pending_acceptance")
public class PendingAcceptance {

    @Id
    @Column(name = "booking_id", nullable = false, updatable = false)
    private UUID bookingId;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @Column(name = "provider_id", nullable = false, updatable = false)
    private UUID providerId;

    /** Carried through to the event for downstream retention windows; may be null. */
    @Column(name = "booking_created_at", updatable = false)
    private Instant bookingCreatedAt;

    @Column(name = "accepted_at", nullable = false, updatable = false)
    private Instant acceptedAt;

    /** Whether the Booking Service has confirmed the PROVIDER_ACCEPTED transition. */
    @Column(name = "booking_accepted", nullable = false)
    private boolean bookingAccepted;

    /** Failed attempts so far; drives the backoff. */
    @Column(name = "attempts", nullable = false)
    private int attempts;

    /** Earliest time the reconciler may (re)try this acceptance. */
    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "last_error", length = 2000)
    private String lastError;

    protected PendingAcceptance() {
        // JPA
    }

    public PendingAcceptance(UUID bookingId, UUID customerId, UUID providerId, Instant bookingCreatedAt,
                             Instant acceptedAt, Instant nextAttemptAt) {
        this.bookingId = bookingId;
        this.customerId = customerId;
        this.providerId = providerId;
        this.bookingCreatedAt = bookingCreatedAt;
        this.acceptedAt = acceptedAt;
        this.nextAttemptAt = nextAttemptAt;
    }

    /** The Booking Service applied the transition; the next attempt only has to announce it. */
    public void markBookingAccepted(Instant retryAt) {
        this.bookingAccepted = true;
        this.nextAttemptAt = retryAt;
    }

    /** Records a failed attempt and when to try again. */
    public void recordFailure(String error, Instant retryAt) {
        this.attempts++;
        this.lastError = error == null || error.length() <= 2000 ? error : error.substring(0, 2000);
        this.nextAttemptAt = retryAt;
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

    public Instant getBookingCreatedAt() {
        return bookingCreatedAt;
    }

    public Instant getAcceptedAt() {
        return acceptedAt;
    }

    public boolean isBookingAccepted() {
        return bookingAccepted;
    }

    public int getAttempts() {
        return attempts;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public String getLastError() {
        return lastError;
    }
}
