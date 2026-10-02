package com.homefix.notification.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homefix.notification.domain.NotificationEventType;
import com.homefix.shared.outbox.kafka.KafkaProducerTemplate;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.Test;

/**
 * Verifies the mapper reads the stable {@code eventId} header, extracts the user ids and non-PII
 * attributes under the field names producers actually use, and treats a missing header or
 * unparseable body as a poison message (routed to the DLQ by the base consumer).
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
    void mapsEventIdParticipantsAndBookingReferenceFromProducerFieldName() {
        UUID eventId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        UUID providerId = UUID.randomUUID();
        // booking-service names the field "reference".
        String json = "{\"bookingId\":\"" + UUID.randomUUID() + "\",\"reference\":\"BR-9\","
                + "\"customerId\":\"" + customerId + "\",\"providerId\":\"" + providerId + "\"}";

        InboundEvent event = mapper.map(NotificationEventType.BOOKING_CREATED, record(json, eventId));

        assertThat(event.eventType()).isEqualTo(NotificationEventType.BOOKING_CREATED);
        assertThat(event.kafkaEventId()).isEqualTo(eventId);
        assertThat(event.participants().customerId()).isEqualTo(customerId);
        assertThat(event.participants().providerId()).isEqualTo(providerId);
        assertThat(event.attributes()).containsEntry("bookingReference", "BR-9");
    }

    @Test
    void legacyBookingReferenceSpellingIsStillAccepted() {
        String json = "{\"bookingReference\":\"BR-10\",\"customerId\":\"" + UUID.randomUUID() + "\"}";

        InboundEvent event = mapper.map(NotificationEventType.JOB_STARTED, record(json, UUID.randomUUID()));

        assertThat(event.attributes()).containsEntry("bookingReference", "BR-10");
    }

    @Test
    void mapsReviewParticipantsAndComplaintStatus() {
        UUID reviewer = UUID.randomUUID();
        UUID reviewee = UUID.randomUUID();
        String review = "{\"reviewerId\":\"" + reviewer + "\",\"revieweeId\":\"" + reviewee + "\"}";
        InboundEvent reviewEvent = mapper.map(NotificationEventType.REVIEW_SUBMITTED,
                record(review, UUID.randomUUID()));
        assertThat(reviewEvent.participants().reviewerId()).isEqualTo(reviewer);
        assertThat(reviewEvent.participants().revieweeId()).isEqualTo(reviewee);

        String complaint = "{\"customerId\":\"" + UUID.randomUUID() + "\",\"newStatus\":\"RESOLVED\"}";
        InboundEvent complaintEvent = mapper.map(NotificationEventType.COMPLAINT_STATUS_CHANGED,
                record(complaint, UUID.randomUUID()));
        assertThat(complaintEvent.attributes()).containsEntry("complaintStatus", "RESOLVED");

        String cancelled = "{\"customerId\":\"" + UUID.randomUUID() + "\",\"status\":\"SEARCHING_FAILED\"}";
        InboundEvent cancelledEvent = mapper.map(NotificationEventType.BOOKING_CANCELLED,
                record(cancelled, UUID.randomUUID()));
        assertThat(cancelledEvent.attributes()).containsEntry("bookingStatus", "SEARCHING_FAILED");
    }

    @Test
    void contactFieldsOnTheEventAreIgnored() {
        // Contact details come from the Auth Service, never from the event body.
        String json = "{\"customerId\":\"" + UUID.randomUUID() + "\",\"mobileNumber\":\"+911\","
                + "\"emailAddress\":\"a@b.c\"}";

        InboundEvent event = mapper.map(NotificationEventType.BOOKING_CREATED, record(json, UUID.randomUUID()));

        assertThat(event.attributes()).doesNotContainKeys("mobileNumber", "emailAddress");
        assertThat(event.attributes().values()).doesNotContain("+911", "a@b.c");
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
}
