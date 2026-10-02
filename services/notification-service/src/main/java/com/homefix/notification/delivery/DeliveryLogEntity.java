package com.homefix.notification.delivery;

import java.time.Instant;
import java.util.UUID;

import com.homefix.notification.domain.DeliveryStatus;
import com.homefix.notification.domain.NotificationChannel;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

/**
 * Persisted delivery-log row for one {@code (kafkaEventId, userId, channel)} delivery
 * (Requirement 17.7).
 *
 * <p>The composite primary key {@code (kafkaEventId, userId, channel)} is the deduplication key:
 * the presence of a row means that event has already been processed for that recipient on that
 * channel, so any redelivery is silently discarded and no duplicate notification is dispatched
 * (Property 22). The recipient is part of the key because one event can notify several people —
 * a cancellation reaches both the customer and the assigned provider on the same channels — and
 * keying on {@code (kafkaEventId, channel)} alone would let the first recipient's row suppress
 * the second recipient's delivery.
 *
 * <p>Records the mandated fields: kafka event id, channel, user id, timestamp, delivery status,
 * retry count, and error description. The error description must never contain PII
 * (Requirement 26.4).
 */
@Entity
@Table(name = "delivery_log")
@IdClass(DeliveryLogEntity.DeliveryLogId.class)
public class DeliveryLogEntity {

    @Id
    @Column(name = "kafka_event_id", nullable = false)
    private UUID kafkaEventId;

    @Id
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 20)
    private NotificationChannel channel;

    @Column(name = "delivered_at", nullable = false)
    private Instant timestamp;

    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_status", nullable = false, length = 30)
    private DeliveryStatus deliveryStatus;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(name = "error_description", length = 500)
    private String errorDescription;

    protected DeliveryLogEntity() {
        // JPA
    }

    public DeliveryLogEntity(UUID kafkaEventId, NotificationChannel channel, UUID userId,
                             Instant timestamp, DeliveryStatus deliveryStatus, int retryCount,
                             String errorDescription) {
        this.kafkaEventId = kafkaEventId;
        this.channel = channel;
        this.userId = userId;
        this.timestamp = timestamp;
        this.deliveryStatus = deliveryStatus;
        this.retryCount = retryCount;
        this.errorDescription = errorDescription;
    }

    public UUID getKafkaEventId() {
        return kafkaEventId;
    }

    public NotificationChannel getChannel() {
        return channel;
    }

    public UUID getUserId() {
        return userId;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public DeliveryStatus getDeliveryStatus() {
        return deliveryStatus;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public String getErrorDescription() {
        return errorDescription;
    }

    /** Composite key {@code (kafkaEventId, userId, channel)} — the deduplication identity. */
    public static class DeliveryLogId implements java.io.Serializable {
        private UUID kafkaEventId;
        private UUID userId;
        private NotificationChannel channel;

        public DeliveryLogId() {
        }

        public DeliveryLogId(UUID kafkaEventId, UUID userId, NotificationChannel channel) {
            this.kafkaEventId = kafkaEventId;
            this.userId = userId;
            this.channel = channel;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof DeliveryLogId that)) {
                return false;
            }
            return java.util.Objects.equals(kafkaEventId, that.kafkaEventId)
                    && java.util.Objects.equals(userId, that.userId)
                    && channel == that.channel;
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(kafkaEventId, userId, channel);
        }
    }
}
