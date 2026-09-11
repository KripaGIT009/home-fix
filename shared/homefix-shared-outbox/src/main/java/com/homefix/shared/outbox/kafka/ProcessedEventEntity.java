package com.homefix.shared.outbox.kafka;

import com.homefix.shared.outbox.OutboxEventEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Persisted record proving a Kafka {@code eventId} has already been processed by a specific
 * consumer group (Task 6, Requirement 22.5).
 *
 * <p>The primary key is the composite {@code (consumerGroup, eventId)} so the same event can
 * be independently consumed by different consumer groups, while a redelivery to the <em>same</em>
 * group is detected and skipped.
 */
@Entity
@Table(name = "processed_event", schema = OutboxEventEntity.SCHEMA)
@IdClass(ProcessedEventEntity.ProcessedEventId.class)
public class ProcessedEventEntity {

    @Id
    @Column(name = "consumer_group", nullable = false, length = 150)
    private String consumerGroup;

    @Id
    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    protected ProcessedEventEntity() {
        // JPA
    }

    public ProcessedEventEntity(String consumerGroup, UUID eventId) {
        this.consumerGroup = consumerGroup;
        this.eventId = eventId;
        this.processedAt = Instant.now();
    }

    public String getConsumerGroup() {
        return consumerGroup;
    }

    public UUID getEventId() {
        return eventId;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }

    /** Composite key for {@link ProcessedEventEntity}. */
    public static class ProcessedEventId implements Serializable {
        private String consumerGroup;
        private UUID eventId;

        public ProcessedEventId() {
        }

        public ProcessedEventId(String consumerGroup, UUID eventId) {
            this.consumerGroup = consumerGroup;
            this.eventId = eventId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof ProcessedEventId that)) {
                return false;
            }
            return Objects.equals(consumerGroup, that.consumerGroup)
                    && Objects.equals(eventId, that.eventId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(consumerGroup, eventId);
        }
    }
}
