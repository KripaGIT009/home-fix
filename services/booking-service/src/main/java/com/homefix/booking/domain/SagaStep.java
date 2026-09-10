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
 * A single recorded step of the Booking Saga (Requirement 24.6).
 *
 * <p>Each step is written to the Saga log <em>before</em> the corresponding action proceeds,
 * and updated to COMPLETED after it commits or COMPENSATED after its compensating transaction
 * runs. On failure, the {@code BookingSagaOrchestrator} replays committed steps in reverse
 * order to compensate (Requirement 24.7).
 */
@Entity
@Table(name = "saga_step", indexes = {
        @Index(name = "idx_saga_step_booking", columnList = "booking_id, sequence_no")
})
public class SagaStep {

    /** Lifecycle of a single saga step. */
    public enum Status {
        STARTED,
        COMPLETED,
        FAILED,
        COMPENSATED
    }

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "booking_id", nullable = false, updatable = false)
    private UUID bookingId;

    /** Monotonic order within a booking's saga; compensation runs in descending order. */
    @Column(name = "sequence_no", nullable = false, updatable = false)
    private int sequenceNo;

    @Column(name = "step_name", nullable = false, updatable = false, length = 80)
    private String stepName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status;

    @Column(name = "recorded_at", nullable = false, updatable = false)
    private Instant recordedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "compensated_at")
    private Instant compensatedAt;

    @Column(name = "detail", length = 2000)
    private String detail;

    protected SagaStep() {
        // JPA
    }

    private SagaStep(UUID bookingId, int sequenceNo, String stepName) {
        this.id = UUID.randomUUID();
        this.bookingId = bookingId;
        this.sequenceNo = sequenceNo;
        this.stepName = stepName;
        this.status = Status.STARTED;
        this.recordedAt = Instant.now();
    }

    /** Records a step as STARTED (called before the action proceeds, Requirement 24.6). */
    public static SagaStep started(UUID bookingId, int sequenceNo, String stepName) {
        return new SagaStep(bookingId, sequenceNo, stepName);
    }

    public void markCompleted() {
        this.status = Status.COMPLETED;
        this.completedAt = Instant.now();
    }

    public void markFailed(String detail) {
        this.status = Status.FAILED;
        this.detail = truncate(detail);
    }

    public void markCompensated() {
        this.status = Status.COMPENSATED;
        this.compensatedAt = Instant.now();
    }

    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= 2000 ? s : s.substring(0, 2000);
    }

    public UUID getId() {
        return id;
    }

    public UUID getBookingId() {
        return bookingId;
    }

    public int getSequenceNo() {
        return sequenceNo;
    }

    public String getStepName() {
        return stepName;
    }

    public Status getStatus() {
        return status;
    }

    public Instant getRecordedAt() {
        return recordedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public Instant getCompensatedAt() {
        return compensatedAt;
    }

    public String getDetail() {
        return detail;
    }
}
