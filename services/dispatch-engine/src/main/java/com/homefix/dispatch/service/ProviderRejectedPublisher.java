package com.homefix.dispatch.service;

import com.homefix.dispatch.event.ProviderRejectedEvent;
import com.homefix.shared.outbox.OutboxEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Publishes {@link ProviderRejectedEvent} through the shared outbox (Requirement 8.7). A declined
 * or unanswered offer changes no local state in the Dispatch Engine, so the outbox row is the whole
 * of the write: it commits in its own transaction and the shared Outbox Processor then relays it to
 * Kafka exactly-once.
 *
 * <p>Like {@link ProviderAcceptedPublisher}, this is a thin seam over {@link OutboxEventPublisher}
 * so {@link DispatchService} can publish without carrying transaction concerns, and so tests can
 * substitute a fake publisher.
 */
@Component
public class ProviderRejectedPublisher {

    private final OutboxEventPublisher outboxEventPublisher;

    public ProviderRejectedPublisher(OutboxEventPublisher outboxEventPublisher) {
        this.outboxEventPublisher = outboxEventPublisher;
    }

    /**
     * Publishes the rejection.
     *
     * @param bookingId  the booking that was offered
     * @param customerId the booking's customer, so the Notification Service can address it
     * @param providerId the provider who declined or did not answer
     * @param reason     {@link ProviderRejectedEvent#REASON_REJECTED} or
     *                   {@link ProviderRejectedEvent#REASON_TIMED_OUT}
     */
    @Transactional
    public void publish(UUID bookingId, UUID customerId, UUID providerId, String reason) {
        ProviderRejectedEvent event = new ProviderRejectedEvent(
                bookingId, customerId, providerId, reason, Instant.now());
        outboxEventPublisher.publish(
                ProviderRejectedEvent.AGGREGATE_TYPE,
                bookingId,
                ProviderRejectedEvent.EVENT_TYPE,
                event);
    }
}
