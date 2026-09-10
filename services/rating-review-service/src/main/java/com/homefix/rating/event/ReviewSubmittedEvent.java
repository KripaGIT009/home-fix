package com.homefix.rating.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Domain event published when a non-flagged review is successfully submitted (Requirement 15.6).
 * Written to the shared outbox so it commits atomically with the review row and is relayed to Kafka
 * exactly-once by the Outbox Processor. Flagged reviews do <em>not</em> emit this event until (and
 * unless) an Admin approves them.
 */
public record ReviewSubmittedEvent(
        UUID reviewId,
        UUID bookingId,
        UUID reviewerId,
        UUID revieweeId,
        String reviewerRole,
        int overallRating,
        Instant submittedAt) {

    /** Kafka topic / event type name for this event. */
    public static final String EVENT_TYPE = "ReviewSubmitted";

    /** Aggregate type used for the outbox row. */
    public static final String AGGREGATE_TYPE = "Review";
}
