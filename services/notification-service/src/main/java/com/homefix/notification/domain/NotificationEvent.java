package com.homefix.notification.domain;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Normalised, in-domain representation of an inbound lifecycle event after it has been parsed
 * from Kafka and its recipient resolved.
 *
 * @param eventType       which of the 11 lifecycle events this is (Requirement 17.5)
 * @param kafkaEventId    the stable Kafka event id, used as the first half of the dedup key
 *                        (Requirement 17.7, Property 22)
 * @param recipientUserId the user who should receive the notification
 * @param contact         the recipient's per-channel contact details (mobile/email/device token)
 * @param attributes      non-PII template attributes (e.g. booking reference) for rendering
 */
public record NotificationEvent(
        NotificationEventType eventType,
        UUID kafkaEventId,
        UUID recipientUserId,
        NotificationContact contact,
        Map<String, String> attributes) {

    public NotificationEvent {
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(kafkaEventId, "kafkaEventId");
        Objects.requireNonNull(recipientUserId, "recipientUserId");
        contact = contact == null ? NotificationContact.empty() : contact;
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}
