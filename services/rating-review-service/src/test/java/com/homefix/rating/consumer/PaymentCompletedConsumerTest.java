package com.homefix.rating.consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.homefix.rating.event.PaymentCompletedEvent;
import com.homefix.rating.service.ReviewService;
import com.homefix.shared.outbox.kafka.DlqForwarder;
import com.homefix.shared.outbox.kafka.KafkaProducerTemplate;
import com.homefix.shared.outbox.kafka.ProcessedEventRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Tests {@link PaymentCompletedConsumer}: a well-formed event opens review prompts via
 * {@link ReviewService}; a duplicate event id is skipped (Requirement 22.5); an unparseable payload
 * is dead-lettered after retries (Requirement 22.6).
 */
@ExtendWith(MockitoExtension.class)
class PaymentCompletedConsumerTest {

    private static final String TOPIC = "PaymentCompleted";

    @Mock
    private ProcessedEventRepository processedEventRepository;
    @Mock
    private DlqForwarder dlqForwarder;
    @Mock
    private ReviewService reviewService;

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private PaymentCompletedConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new PaymentCompletedConsumer(processedEventRepository, dlqForwarder, mapper,
                reviewService);
    }

    private ConsumerRecord<String, String> record(UUID eventId, String value) {
        ConsumerRecord<String, String> rec = new ConsumerRecord<>(TOPIC, 0, 0L, "key", value);
        rec.headers().add(new RecordHeader(KafkaProducerTemplate.HEADER_EVENT_ID,
                eventId.toString().getBytes(StandardCharsets.UTF_8)));
        return rec;
    }

    @Test
    void wellFormedEvent_opensReviewPrompts() throws Exception {
        UUID eventId = UUID.randomUUID();
        PaymentCompletedEvent event = new PaymentCompletedEvent(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("100.00"), new BigDecimal("10.00"),
                new BigDecimal("90.00"), "UPI", Instant.parse("2024-06-01T12:00:00Z"));
        when(processedEventRepository.existsByConsumerGroupAndEventId(anyString(), eq(eventId)))
                .thenReturn(false);

        consumer.onMessage(record(eventId, mapper.writeValueAsString(event)));

        verify(reviewService).openReviewPrompts(any(PaymentCompletedEvent.class));
        verify(processedEventRepository).save(any());
        verify(dlqForwarder, never()).forward(any(), any(), any(), any(), any());
    }

    @Test
    void duplicateEventId_isSkipped() {
        UUID eventId = UUID.randomUUID();
        when(processedEventRepository.existsByConsumerGroupAndEventId(anyString(), eq(eventId)))
                .thenReturn(true);

        consumer.onMessage(record(eventId, "{}"));

        verify(reviewService, never()).openReviewPrompts(any());
    }

    @Test
    void unparseablePayload_isDeadLettered() {
        UUID eventId = UUID.randomUUID();
        when(processedEventRepository.existsByConsumerGroupAndEventId(anyString(), eq(eventId)))
                .thenReturn(false);

        consumer.onMessage(record(eventId, "not-json"));

        verify(reviewService, never()).openReviewPrompts(any());
        verify(dlqForwarder).forward(eq(TOPIC), eq("key"), eq(eventId.toString()), eq("not-json"), anyString());
    }
}
