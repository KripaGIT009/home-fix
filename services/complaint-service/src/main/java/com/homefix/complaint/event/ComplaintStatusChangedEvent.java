package com.homefix.complaint.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Domain event published when a complaint's status changes (Requirement 16.3, 16.9). Written to
 * the shared outbox so it commits atomically with the status change and is relayed to Kafka
 * exactly-once by the Outbox Processor. Downstream consumers include the Notification Service (in-app
 * status-change notification, 16.3) and Admin reporting (stats refresh, 16.9).
 */
public record ComplaintStatusChangedEvent(
        UUID complaintId,
        UUID bookingId,
        UUID customerId,
        String previousStatus,
        String newStatus,
        Instant changedAt) {

    /** Kafka topic / event type name for this event. */
    public static final String EVENT_TYPE = "ComplaintStatusChanged";

    /** Aggregate type used for the outbox row. */
    public static final String AGGREGATE_TYPE = "Complaint";
}
