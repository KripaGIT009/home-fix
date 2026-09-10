package com.homefix.booking.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Kafka event payloads published on job-execution milestone transitions (Requirement 9.3-9.5,
 * 9.10, 22.1). Each is written to the transactional outbox within the same transaction as the
 * corresponding state change so delivery is at-least-once and consistent with the state.
 *
 * <p>State → event mapping (Requirement 22.1):
 * <pre>
 *   PROVIDER_ON_THE_WAY -> ProviderArriving
 *   PROVIDER_ARRIVED    -> ProviderArrived
 *   JOB_STARTED         -> JobStarted
 *   JOB_COMPLETED       -> JobCompleted
 * </pre>
 */
public final class JobExecutionEvents {

    public static final String AGGREGATE_TYPE = "Booking";

    private JobExecutionEvents() {
    }

    /** Published when the provider marks themselves on the way (PROVIDER_ON_THE_WAY). */
    public record ProviderArriving(UUID bookingId, String reference, UUID providerId, Instant occurredAt) {
        public static final String EVENT_TYPE = "ProviderArriving";
    }

    /** Published when the provider confirms arrival (PROVIDER_ARRIVED). */
    public record ProviderArrived(UUID bookingId, String reference, UUID providerId, Instant occurredAt) {
        public static final String EVENT_TYPE = "ProviderArrived";
    }

    /** Published when the provider starts work (JOB_STARTED), carrying the job start timestamp. */
    public record JobStarted(UUID bookingId, String reference, UUID providerId,
                             Instant startedAt, Instant occurredAt) {
        public static final String EVENT_TYPE = "JobStarted";
    }

    /**
     * Published when the job completes (JOB_COMPLETED), carrying the net working duration and
     * the final total (Requirement 11.6).
     */
    public record JobCompleted(UUID bookingId, String reference, UUID providerId,
                               Instant completedAt, long netDurationSeconds,
                               java.math.BigDecimal finalTotal, Instant occurredAt) {
        public static final String EVENT_TYPE = "JobCompleted";
    }
}
