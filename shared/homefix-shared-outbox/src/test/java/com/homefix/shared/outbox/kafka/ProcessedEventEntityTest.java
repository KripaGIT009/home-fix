package com.homefix.shared.outbox.kafka;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ProcessedEventEntity} and its composite {@code (consumerGroup, eventId)}
 * key (Task 6, Requirement 22.5). Correct key equality is what lets the same event be consumed
 * independently by different groups while a redelivery to the same group is detected.
 */
class ProcessedEventEntityTest {

    @Test
    void capturesConsumerGroupEventIdAndProcessedTime() {
        UUID eventId = UUID.randomUUID();

        ProcessedEventEntity entity = new ProcessedEventEntity("dispatch-engine", eventId);

        assertThat(entity.getConsumerGroup()).isEqualTo("dispatch-engine");
        assertThat(entity.getEventId()).isEqualTo(eventId);
        assertThat(entity.getProcessedAt()).isNotNull();
    }

    @Test
    void compositeKeyEqualsAndHashCodeUseBothFields() {
        UUID eventId = UUID.randomUUID();
        var a = new ProcessedEventEntity.ProcessedEventId("group-a", eventId);
        var sameAsA = new ProcessedEventEntity.ProcessedEventId("group-a", eventId);
        var differentGroup = new ProcessedEventEntity.ProcessedEventId("group-b", eventId);
        var differentEvent = new ProcessedEventEntity.ProcessedEventId("group-a", UUID.randomUUID());

        assertThat(a).isEqualTo(sameAsA);
        assertThat(a.hashCode()).isEqualTo(sameAsA.hashCode());
        assertThat(a).isNotEqualTo(differentGroup);
        assertThat(a).isNotEqualTo(differentEvent);
        assertThat(a).isEqualTo(a);
        assertThat(a).isNotEqualTo(null);
        assertThat(a).isNotEqualTo("not-an-id");
    }

    @Test
    void noArgKeyConstructorIsUsableByJpa() {
        var id = new ProcessedEventEntity.ProcessedEventId();
        assertThat(id).isNotNull();
    }
}
