package com.homefix.notification.consumer;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homefix.notification.domain.NotificationContact;
import com.homefix.notification.domain.NotificationEvent;
import com.homefix.notification.domain.NotificationEventType;
import com.homefix.shared.outbox.kafka.KafkaProducerTemplate;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.stereotype.Component;

/**
 * Parses a raw Kafka record into a domain {@link NotificationEvent}: reads the stable
 * {@code eventId} header (the first half of the dedup key), deserialises the payload, and
 * resolves the recipient and non-PII rendering attributes.
 *
 * <p>A record lacking a usable {@code eventId} header or with an unparseable body is a poison
 * message; mapping throws so the shared consumer routes it to the dead-letter topic after
 * exhausting retries.
 */
@Component
public class NotificationEventMapper {

    private final ObjectMapper objectMapper;

    public NotificationEventMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public NotificationEvent map(NotificationEventType eventType, ConsumerRecord<String, String> record) {
        UUID eventId = extractEventId(record);
        LifecycleEventPayload payload = parse(record.value());
        UUID recipient = payload.resolveRecipient();
        if (recipient == null) {
            throw new IllegalStateException("Lifecycle event has no resolvable recipient");
        }

        NotificationContact contact = new NotificationContact(
                payload.mobileNumber(), payload.emailAddress(), payload.deviceToken());

        Map<String, String> attributes = new HashMap<>();
        if (payload.bookingReference() != null) {
            attributes.put("bookingReference", payload.bookingReference());
        }

        return new NotificationEvent(eventType, eventId, recipient, contact, attributes);
    }

    private LifecycleEventPayload parse(String json) {
        try {
            return objectMapper.readValue(json, LifecycleEventPayload.class);
        } catch (Exception e) {
            throw new IllegalStateException("Unparseable lifecycle event payload", e);
        }
    }

    private static UUID extractEventId(ConsumerRecord<String, String> record) {
        Header header = record.headers().lastHeader(KafkaProducerTemplate.HEADER_EVENT_ID);
        if (header == null || header.value() == null) {
            throw new IllegalStateException("Lifecycle event record is missing the "
                    + KafkaProducerTemplate.HEADER_EVENT_ID + " header");
        }
        try {
            return UUID.fromString(new String(header.value(), StandardCharsets.UTF_8));
        } catch (IllegalArgumentException malformed) {
            throw new IllegalStateException("Lifecycle event has a malformed "
                    + KafkaProducerTemplate.HEADER_EVENT_ID + " header", malformed);
        }
    }
}
