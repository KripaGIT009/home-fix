package com.homefix.shared.outbox.kafka;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * Repository over the {@code processed_event} dedup table (Task 6, Requirement 22.5).
 */
public interface ProcessedEventRepository
        extends JpaRepository<ProcessedEventEntity, ProcessedEventEntity.ProcessedEventId> {

    boolean existsByConsumerGroupAndEventId(String consumerGroup, UUID eventId);
}
