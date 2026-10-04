package com.homefix.dispatch.service;

import com.homefix.dispatch.event.ProviderAcceptedEvent;
import com.homefix.shared.outbox.OutboxEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Publishes {@link ProviderAcceptedEvent} through the shared outbox (Requirement 8.6); the shared
 * Outbox Processor then relays it to Kafka exactly-once.
 *
 * <p>The only caller is {@code JpaAcceptanceLedger#announce}, whose transaction this joins: the
 * outbox row is written in the same transaction that removes the acceptance's pending record, so
 * the event exists if and only if the record is gone (see
 * {@link com.homefix.dispatch.port.AcceptanceLedger}). Called without a transaction it opens its
 * own.
 */
@Component
public class ProviderAcceptedPublisher {

    private final OutboxEventPublisher outboxEventPublisher;

    public ProviderAcceptedPublisher(OutboxEventPublisher outboxEventPublisher) {
        this.outboxEventPublisher = outboxEventPublisher;
    }

    /**
     * Publishes the acceptance.
     *
     * @param bookingId        the booking that was accepted
     * @param customerId       the booking's customer; required by the Chat Service to activate the
     *                         channel, which needs both participants
     * @param providerId       the provider who accepted
     * @param bookingCreatedAt when the booking was created, used downstream for retention windows
     */
    @Transactional
    public void publish(UUID bookingId, UUID customerId, UUID providerId, Instant bookingCreatedAt) {
        ProviderAcceptedEvent event = new ProviderAcceptedEvent(
                bookingId, customerId, providerId, bookingCreatedAt, Instant.now());
        outboxEventPublisher.publish(
                ProviderAcceptedEvent.AGGREGATE_TYPE,
                bookingId,
                ProviderAcceptedEvent.EVENT_TYPE,
                event);
    }
}
