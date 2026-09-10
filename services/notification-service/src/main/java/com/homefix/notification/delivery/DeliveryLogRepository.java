package com.homefix.notification.delivery;

import java.util.UUID;

import com.homefix.notification.domain.NotificationChannel;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository over the {@code delivery_log} table (Requirement 17.7).
 */
public interface DeliveryLogRepository
        extends JpaRepository<DeliveryLogEntity, DeliveryLogEntity.DeliveryLogId> {

    boolean existsByKafkaEventIdAndChannel(UUID kafkaEventId, NotificationChannel channel);
}
