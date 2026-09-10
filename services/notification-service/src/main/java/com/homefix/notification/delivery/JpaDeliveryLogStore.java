package com.homefix.notification.delivery;

import java.util.UUID;

import com.homefix.notification.domain.NotificationChannel;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * JPA-backed {@link DeliveryLogStore}. The uniqueness of the {@code (kafkaEventId, channel)}
 * primary key is what enforces deduplication at the storage layer, so even a concurrent
 * redelivery cannot double-insert (Property 22).
 */
@Component
public class JpaDeliveryLogStore implements DeliveryLogStore {

    private final DeliveryLogRepository repository;

    public JpaDeliveryLogStore(DeliveryLogRepository repository) {
        this.repository = repository;
    }

    @Override
    public boolean alreadyDelivered(UUID kafkaEventId, NotificationChannel channel) {
        return repository.existsByKafkaEventIdAndChannel(kafkaEventId, channel);
    }

    @Override
    public void record(DeliveryLogEntity entry) {
        try {
            repository.save(entry);
        } catch (DataIntegrityViolationException raceLost) {
            // Another instance persisted the same (kafkaEventId, channel) concurrently — the
            // dedup guarantee still holds, so treat this as a no-op.
        }
    }
}
