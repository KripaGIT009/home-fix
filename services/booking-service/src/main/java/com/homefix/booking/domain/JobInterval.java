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
 * A single closed or open time segment during job execution (Requirement 11.5, 11.6).
 *
 * <p>Each time a job is (re)started a {@link Kind#WORK} interval is opened; when the provider
 * pauses, that WORK interval is closed and a {@link Kind#PAUSE} interval is opened; on resume
 * the PAUSE interval is closed and a new WORK interval opened. On completion the final WORK
 * interval is closed. The ordered sequence of intervals for a booking is the input to the net
 * job duration calculation (Property 10): net = Σ WORK durations − Σ PAUSE durations.
 */
@Entity
@Table(name = "job_interval", indexes = {
        @Index(name = "idx_job_interval_booking", columnList = "booking_id, started_at")
})
public class JobInterval {

    /** Whether the segment is active working time or a pause. */
    public enum Kind {
        WORK,
        PAUSE
    }

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "booking_id", nullable = false, updatable = false)
    private UUID bookingId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 10)
    private Kind kind;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    /** Null while the interval is still open (in progress). */
    @Column(name = "ended_at")
    private Instant endedAt;

    /** Mandatory pause reason (1-500 chars) for PAUSE intervals; null for WORK (Requirement 11.5). */
    @Column(name = "reason", length = 500)
    private String reason;

    protected JobInterval() {
        // JPA
    }

    private JobInterval(UUID bookingId, Kind kind, Instant startedAt, String reason) {
        this.id = UUID.randomUUID();
        this.bookingId = bookingId;
        this.kind = kind;
        this.startedAt = startedAt;
        this.reason = reason;
    }

    /** Opens a WORK interval starting at {@code startedAt}. */
    public static JobInterval work(UUID bookingId, Instant startedAt) {
        return new JobInterval(bookingId, Kind.WORK, startedAt, null);
    }

    /** Opens a PAUSE interval starting at {@code startedAt} with the mandatory reason. */
    public static JobInterval pause(UUID bookingId, Instant startedAt, String reason) {
        return new JobInterval(bookingId, Kind.PAUSE, startedAt, reason);
    }

    /** Closes this interval at {@code endedAt}. */
    public void close(Instant endedAt) {
        this.endedAt = endedAt;
    }

    public boolean isOpen() {
        return endedAt == null;
    }

    public UUID getId() {
        return id;
    }

    public UUID getBookingId() {
        return bookingId;
    }

    public Kind getKind() {
        return kind;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getEndedAt() {
        return endedAt;
    }

    public String getReason() {
        return reason;
    }
}
