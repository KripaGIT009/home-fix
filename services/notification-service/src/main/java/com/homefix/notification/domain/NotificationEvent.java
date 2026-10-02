package com.homefix.notification.domain;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Normalised, in-domain representation of one inbound event addressed to one recipient, after it
 * has been parsed from Kafka, its recipients decided by the {@link RecipientPolicy}, and the
 * recipient's contact details resolved. An event with several recipients yields one instance per
 * recipient, all sharing the same {@code kafkaEventId}.
 *
 * @param eventType       which event this is (Requirements 16.2, 16.3, 17.5)
 * @param kafkaEventId    the stable Kafka event id, used as the first part of the dedup key
 *                        (Requirement 17.7, Property 22)
 * @param recipientUserId the user who should receive the notification
 * @param audience        the part the recipient plays, which selects the message variant
 * @param contact         the recipient's per-channel contact details (mobile/email/device token)
 * @param attributes      non-PII template attributes (e.g. booking reference) for rendering
 */
public record NotificationEvent(
        NotificationEventType eventType,
        UUID kafkaEventId,
        UUID recipientUserId,
        NotificationAudience audience,
        NotificationContact contact,
        Map<String, String> attributes) {

    public NotificationEvent {
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(kafkaEventId, "kafkaEventId");
        Objects.requireNonNull(recipientUserId, "recipientUserId");
        Objects.requireNonNull(audience, "audience");
        contact = contact == null ? NotificationContact.empty() : contact;
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}
