package com.homefix.dispatch.service;

import com.homefix.dispatch.event.ProviderAcceptedEvent;
import com.homefix.shared.outbox.OutboxEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Publishes {@link ProviderAcceptedEvent} through the shared outbox (Requirement 8.6). The write
 * happens inside its own transaction so the PENDING outbox row is committed atomically; the shared
 * Outbox Processor then relays it to Kafka exactly-once.
 *
 * <p>This is a thin seam over {@link OutboxEventPublisher} so {@link DispatchService} can publish
 * without carrying transaction concerns, and so tests can substitute a fake publisher.
 */
@Component
public class ProviderAcceptedPublisher {

    private final OutboxEventPublisher outboxEventPublisher;

    public ProviderAcceptedPublisher(OutboxEventPublisher outboxEventPublisher) {
        this.outboxEventPublisher = outboxEventPublisher;
    }

    @Transactional
    public void publish(UUID bookingId, UUID providerId) {
        ProviderAcceptedEvent event = new ProviderAcceptedEvent(bookingId, providerId, Instant.now());
        outboxEventPublisher.publish(
                ProviderAcceptedEvent.AGGREGATE_TYPE,
                bookingId,
                ProviderAcceptedEvent.EVENT_TYPE,
                event);
    }
}
