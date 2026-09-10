package com.homefix.complaint.event;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.homefix.complaint.domain.Complaint;
import com.homefix.complaint.domain.ComplaintStatus;
import com.homefix.shared.outbox.OutboxEventPublisher;

/**
 * Publishes complaint domain events through the shared outbox (Requirement 16.1, 16.3, 16.9). Runs
 * with {@link Propagation#MANDATORY} so it always joins the caller's transaction, guaranteeing the
 * PENDING outbox row commits atomically with the complaint write; the shared Outbox Processor then
 * relays it to Kafka exactly-once.
 *
 * <p>Thin seam over {@link OutboxEventPublisher} so {@code ComplaintService} can publish without
 * carrying serialization concerns, and so tests can substitute a fake publisher.
 */
@Component
public class ComplaintEventPublisher {

    private final OutboxEventPublisher outboxEventPublisher;

    public ComplaintEventPublisher(OutboxEventPublisher outboxEventPublisher) {
        this.outboxEventPublisher = outboxEventPublisher;
    }

    /** Publishes {@link ComplaintCreatedEvent} for a newly-created complaint (Requirement 16.1). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void publishCreated(Complaint complaint) {
        ComplaintCreatedEvent event = new ComplaintCreatedEvent(
                complaint.getId(),
                complaint.getBookingId(),
                complaint.getCustomerId(),
                complaint.getAgentId(),
                complaint.getCategory().name(),
                complaint.getCreatedAt());
        outboxEventPublisher.publish(
                ComplaintCreatedEvent.AGGREGATE_TYPE,
                complaint.getId(),
                ComplaintCreatedEvent.EVENT_TYPE,
                event);
    }

    /** Publishes {@link ComplaintStatusChangedEvent} for a status transition (16.3, 16.9). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void publishStatusChanged(Complaint complaint, ComplaintStatus previousStatus,
                                     Instant changedAt) {
        ComplaintStatusChangedEvent event = new ComplaintStatusChangedEvent(
                complaint.getId(),
                complaint.getBookingId(),
                complaint.getCustomerId(),
                previousStatus == null ? null : previousStatus.name(),
                complaint.getStatus().name(),
                changedAt);
        outboxEventPublisher.publish(
                ComplaintStatusChangedEvent.AGGREGATE_TYPE,
                complaint.getId(),
                ComplaintStatusChangedEvent.EVENT_TYPE,
                event);
    }
}
