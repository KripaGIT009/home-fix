package com.homefix.notification.domain;

/**
 * The delivery channels supported by the Notification Service (Requirement 17.1).
 *
 * <p>Deduplication is keyed on {@code (kafkaEventId, channel)}, so the channel is part of the
 * delivery-log identity (Requirement 17.7, Property 22).
 */
public enum NotificationChannel {
    PUSH,
    SMS,
    EMAIL,
    IN_APP
}
