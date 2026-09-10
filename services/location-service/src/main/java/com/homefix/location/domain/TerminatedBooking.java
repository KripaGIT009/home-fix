package com.homefix.location.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Marks a Booking whose location feed has been terminated because it transitioned to
 * JOB_STARTED (Requirement 10.5). Once a Booking is recorded here the service stops accepting
 * location updates for it. Persisting the marker (rather than holding it only in memory) keeps
 * the JobStarted decision durable across restarts and consistent across service instances.
 */
@Entity
@Table(name = "terminated_booking")
public class TerminatedBooking {

    @Id
    @Column(name = "booking_id", nullable = false, updatable = false)
    private UUID bookingId;

    @Column(name = "terminated_at", nullable = false, updatable = false)
    private Instant terminatedAt;

    protected TerminatedBooking() {
        // JPA
    }

    public TerminatedBooking(UUID bookingId, Instant terminatedAt) {
        this.bookingId = bookingId;
        this.terminatedAt = terminatedAt;
    }

    public UUID getBookingId() {
        return bookingId;
    }

    public Instant getTerminatedAt() {
        return terminatedAt;
    }
}
