package com.homefix.notification.consumer;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homefix.notification.domain.NotificationEventType;
import com.homefix.shared.outbox.kafka.KafkaProducerTemplate;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.stereotype.Component;

/**
 * Parses a raw Kafka record into an {@link InboundEvent}: reads the stable {@code eventId} header
 * (the first part of the dedup key), deserialises the payload, and extracts the user ids the event
 * names and its non-PII rendering attributes. Deciding who is notified and resolving their contact
 * details happen afterwards, in the {@code RecipientResolver}; this class does no I/O.
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

    public InboundEvent map(NotificationEventType eventType, ConsumerRecord<String, String> record) {
        UUID eventId = extractEventId(record);
        LifecycleEventPayload payload = parse(record.value());

        Map<String, String> attributes = new HashMap<>();
        if (payload.reference() != null) {
            attributes.put("bookingReference", payload.reference());
        }
        if (payload.newStatus() != null) {
            attributes.put("complaintStatus", payload.newStatus());
        }
        if (payload.status() != null) {
            attributes.put("bookingStatus", payload.status());
        }

        return new InboundEvent(eventType, eventId, payload.participants(), attributes);
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
