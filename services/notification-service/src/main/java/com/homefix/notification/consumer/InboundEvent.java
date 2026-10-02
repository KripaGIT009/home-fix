package com.homefix.notification.consumer;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.homefix.notification.domain.EventParticipants;
import com.homefix.notification.domain.NotificationEventType;

/**
 * A consumed event after parsing and before addressing: its type, stable Kafka event id, the user
 * ids it names, and its non-PII rendering attributes.
 *
 * @param eventType    which event this is
 * @param kafkaEventId the stable event id from the record header (dedup key, Property 22)
 * @param participants the user ids the event names
 * @param attributes   non-PII template attributes (booking reference, complaint status)
 */
public record InboundEvent(
        NotificationEventType eventType,
        UUID kafkaEventId,
        EventParticipants participants,
        Map<String, String> attributes) {

    public InboundEvent {
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(kafkaEventId, "kafkaEventId");
        Objects.requireNonNull(participants, "participants");
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}
