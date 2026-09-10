package com.homefix.complaint.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Domain event published when a complaint is created (Requirement 16.1). Written to the shared
 * outbox so it commits atomically with the complaint row and is relayed to Kafka exactly-once by
 * the Outbox Processor. The Notification Service consumes it to acknowledge the customer within the
 * 30-minute SLA (Requirement 16.2).
 */
public record ComplaintCreatedEvent(
        UUID complaintId,
        UUID bookingId,
        UUID customerId,
        UUID agentId,
        String category,
        Instant createdAt) {

    /** Kafka topic / event type name for this event. */
    public static final String EVENT_TYPE = "ComplaintCreated";

    /** Aggregate type used for the outbox row. */
    public static final String AGGREGATE_TYPE = "Complaint";
}
