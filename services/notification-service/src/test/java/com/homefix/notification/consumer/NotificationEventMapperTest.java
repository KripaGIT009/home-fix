package com.homefix.notification.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homefix.notification.domain.NotificationEvent;
import com.homefix.notification.domain.NotificationEventType;
import com.homefix.shared.outbox.kafka.KafkaProducerTemplate;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.Test;

/**
 * Verifies the mapper reads the stable {@code eventId} header, resolves the recipient, and treats
 * a missing header or unparseable body as a poison message (routed to the DLQ by the base
 * consumer).
 */
class NotificationEventMapperTest {

    private final NotificationEventMapper mapper = new NotificationEventMapper(new ObjectMapper());

    private ConsumerRecord<String, String> record(String value, UUID eventId) {
        ConsumerRecord<String, String> record =
                new ConsumerRecord<>("BookingCreated", 0, 0L, "key", value);
        if (eventId != null) {
            record.headers().add(new RecordHeader(KafkaProducerTemplate.HEADER_EVENT_ID,
                    eventId.toString().getBytes(StandardCharsets.UTF_8)));
        }
        return record;
    }

    @Test
    void mapsEventIdRecipientAndAttributes() {
        UUID eventId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        String json = "{\"bookingId\":\"" + UUID.randomUUID() + "\",\"bookingReference\":\"BR-9\","
                + "\"customerId\":\"" + customerId + "\",\"mobileNumber\":\"+911\"}";

        NotificationEvent event = mapper.map(NotificationEventType.BOOKING_CREATED, record(json, eventId));

        assertThat(event.kafkaEventId()).isEqualTo(eventId);
        assertThat(event.recipientUserId()).isEqualTo(customerId);
        assertThat(event.attributes()).containsEntry("bookingReference", "BR-9");
        assertThat(event.contact().mobileNumber()).isEqualTo("+911");
    }

    @Test
    void explicitRecipientOverridesCustomerId() {
        UUID recipient = UUID.randomUUID();
        String json = "{\"recipientUserId\":\"" + recipient + "\",\"customerId\":\"" + UUID.randomUUID() + "\"}";

        NotificationEvent event = mapper.map(NotificationEventType.JOB_STARTED, record(json, UUID.randomUUID()));

        assertThat(event.recipientUserId()).isEqualTo(recipient);
    }

    @Test
    void missingEventIdHeaderIsPoison() {
        String json = "{\"customerId\":\"" + UUID.randomUUID() + "\"}";
        assertThatThrownBy(() -> mapper.map(NotificationEventType.BOOKING_CREATED, record(json, null)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void unparseableBodyIsPoison() {
        assertThatThrownBy(() -> mapper.map(NotificationEventType.BOOKING_CREATED,
                record("not-json", UUID.randomUUID())))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void missingRecipientIsPoison() {
        assertThatThrownBy(() -> mapper.map(NotificationEventType.BOOKING_CREATED,
                record("{}", UUID.randomUUID())))
                .isInstanceOf(IllegalStateException.class);
    }
}
