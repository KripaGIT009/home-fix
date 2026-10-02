package com.homefix.notification.delivery;

import java.util.UUID;

import com.homefix.notification.domain.NotificationChannel;

/**
 * Abstraction over the delivery-log persistence used for deduplication and audit
 * (Requirement 17.7, Property 22).
 *
 * <p>Depending on this port rather than a JPA repository directly keeps the dedup/retry
 * orchestration testable with a simple in-memory store — no database required.
 */
public interface DeliveryLogStore {

    /**
     * Whether a delivery has already been recorded for {@code (kafkaEventId, userId, channel)}.
     * A {@code true} result means the event was already processed for that recipient on that
     * channel and the redelivery must be silently discarded.
     */
    boolean alreadyDelivered(UUID kafkaEventId, UUID userId, NotificationChannel channel);

    /**
     * Persists a delivery-log row. Implementations must treat
     * {@code (kafkaEventId, userId, channel)} as the primary key so a concurrent duplicate insert
     * is rejected by the store.
     */
    void record(DeliveryLogEntity entry);
}
