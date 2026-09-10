package com.homefix.outbox.relay;

import java.util.Map;

import com.homefix.outbox.config.OutboxProcessorProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link EventTopicResolver}: explicit mappings win; unmapped event types fall
 * back to the configured default topic.
 */
class EventTopicResolverTest {

    @Test
    void usesExplicitMappingWhenPresent() {
        OutboxProcessorProperties.Topics topics = new OutboxProcessorProperties.Topics();
        topics.setMapping(Map.of("BookingCreated", "BookingCreated"));
        topics.setDefaultTopic("domain-events");
        EventTopicResolver resolver = new EventTopicResolver(topics);

        assertThat(resolver.resolve("BookingCreated")).isEqualTo("BookingCreated");
    }

    @Test
    void fallsBackToDefaultTopicWhenUnmapped() {
        OutboxProcessorProperties.Topics topics = new OutboxProcessorProperties.Topics();
        topics.setDefaultTopic("domain-events");
        EventTopicResolver resolver = new EventTopicResolver(topics);

        assertThat(resolver.resolve("SomethingNew")).isEqualTo("domain-events");
    }
}
