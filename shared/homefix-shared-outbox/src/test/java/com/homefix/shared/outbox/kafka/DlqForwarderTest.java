package com.homefix.shared.outbox.kafka;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link DlqForwarder}: derives the {@code .DLT} topic and attaches the
 * tracing headers so a poison message can be traced (Task 6, Requirement 22.6).
 */
class DlqForwarderTest {

    private static final String SOURCE_TOPIC = "booking.created";

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
    private DlqForwarder forwarder;

    @BeforeEach
    void setUp() {
        forwarder = new DlqForwarder(kafkaTemplate);
    }

    @Test
    void deadLetterTopicSuffixesSourceTopic() {
        assertThat(DlqForwarder.deadLetterTopicFor(SOURCE_TOPIC))
                .isEqualTo("booking.created.DLT");
    }

    @Test
    void forwardsToDeadLetterTopicPreservingKeyAndPayload() {
        UUID eventId = UUID.randomUUID();

        forwarder.forward(SOURCE_TOPIC, "booking-1", eventId.toString(),
                "{\"bookingId\":\"booking-1\"}", "handler threw NullPointerException");

        ProducerRecord<String, String> sent = captureSentRecord();
        assertThat(sent.topic()).isEqualTo("booking.created.DLT");
        assertThat(sent.key()).isEqualTo("booking-1");
        assertThat(sent.value()).isEqualTo("{\"bookingId\":\"booking-1\"}");
        assertThat(headerValue(sent, DlqForwarder.HEADER_EVENT_ID)).isEqualTo(eventId.toString());
        assertThat(headerValue(sent, DlqForwarder.HEADER_ORIGINAL_TOPIC)).isEqualTo(SOURCE_TOPIC);
        assertThat(headerValue(sent, DlqForwarder.HEADER_DLQ_REASON))
                .isEqualTo("handler threw NullPointerException");
    }

    @Test
    void forwardsWithoutEventIdHeaderWhenIdIsNull() {
        forwarder.forward(SOURCE_TOPIC, "booking-1", null, "{}", null);

        ProducerRecord<String, String> sent = captureSentRecord();
        assertThat(sent.topic()).isEqualTo("booking.created.DLT");
        // No eventId header is attached when the source record had none.
        assertThat(sent.headers().lastHeader(DlqForwarder.HEADER_EVENT_ID)).isNull();
        // A missing reason falls back to a stable placeholder.
        assertThat(headerValue(sent, DlqForwarder.HEADER_DLQ_REASON)).isEqualTo("unknown");
    }

    @SuppressWarnings("unchecked")
    private ProducerRecord<String, String> captureSentRecord() {
        ArgumentCaptor<ProducerRecord<String, String>> captor =
                ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(captor.capture());
        return captor.getValue();
    }

    private static String headerValue(ProducerRecord<String, String> record, String key) {
        Header header = record.headers().lastHeader(key);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
