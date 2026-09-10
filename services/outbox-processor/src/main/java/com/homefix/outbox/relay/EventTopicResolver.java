package com.homefix.outbox.relay;

import com.homefix.outbox.config.OutboxProcessorProperties;

/**
 * Resolves the Kafka destination topic for an outbox row from its {@code eventType}.
 *
 * <p>Uses the explicit {@code homefix.outbox-processor.topics.mapping} overrides first and falls
 * back to the configured default topic when no mapping is present, so a new event type is
 * relayable without a code change.
 */
public class EventTopicResolver {

    private final OutboxProcessorProperties.Topics topics;

    public EventTopicResolver(OutboxProcessorProperties.Topics topics) {
        this.topics = topics;
    }

    /**
     * @param eventType the outbox row's event type (e.g. {@code BookingCreated})
     * @return the Kafka topic the event should be published to
     */
    public String resolve(String eventType) {
        String mapped = topics.getMapping().get(eventType);
        return mapped != null ? mapped : topics.getDefaultTopic();
    }
}
