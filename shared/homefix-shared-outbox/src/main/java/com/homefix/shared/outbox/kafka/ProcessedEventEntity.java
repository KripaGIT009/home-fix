package com.homefix.shared.outbox.kafka;

import com.homefix.shared.outbox.OutboxEventEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

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
 *
 * <p><b>Why {@link Persistable}.</b> The key is assigned, not generated, so Spring Data would
 * otherwise treat every instance as existing and {@code save} it with {@code merge}: a
 * {@code SELECT} followed by an {@code UPDATE} when the row is already there. A duplicate would
 * then be absorbed silently, and {@link IdempotentKafkaConsumer} relies on the opposite: the
 * insert of an already-recorded {@code (consumerGroup, eventId)} must fail on the primary key so
 * that a concurrent or repeated delivery is recognised. Reporting a freshly constructed instance
 * as new makes {@code save} a plain {@code persist}, i.e. an {@code INSERT}.
 */
@Entity
@Table(name = "processed_event", schema = OutboxEventEntity.SCHEMA)
@IdClass(ProcessedEventEntity.ProcessedEventId.class)
public class ProcessedEventEntity implements Persistable<ProcessedEventEntity.ProcessedEventId> {

    @Id
    @Column(name = "consumer_group", nullable = false, length = 150)
    private String consumerGroup;

    @Id
    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    /** True until the row has been inserted or loaded; never persisted. */
    @Transient
    private boolean isNew = true;

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

    @Override
    public ProcessedEventId getId() {
        return new ProcessedEventId(consumerGroup, eventId);
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
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
