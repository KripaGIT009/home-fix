package com.homefix.invoice.consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.homefix.invoice.event.PaymentCompletedEvent;
import com.homefix.invoice.service.InvoiceService;
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

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Tests {@link PaymentCompletedConsumer}: a well-formed event is parsed and handed to
 * {@link InvoiceService}; a duplicate event id is skipped (Requirement 22.5); an unparseable
 * payload is dead-lettered after retries (Requirement 22.6).
 */
@ExtendWith(MockitoExtension.class)
class PaymentCompletedConsumerTest {

    private static final String TOPIC = "PaymentCompleted";

    @Mock
    private ProcessedEventRepository processedEventRepository;
    @Mock
    private DlqForwarder dlqForwarder;
    @Mock
    private InvoiceService invoiceService;

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private PaymentCompletedConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new PaymentCompletedConsumer(processedEventRepository, dlqForwarder, mapper,
                invoiceService);
    }

    private ConsumerRecord<String, String> record(UUID eventId, String value) {
        ConsumerRecord<String, String> rec = new ConsumerRecord<>(TOPIC, 0, 0L, "key", value);
        rec.headers().add(new RecordHeader(KafkaProducerTemplate.HEADER_EVENT_ID,
                eventId.toString().getBytes(StandardCharsets.UTF_8)));
        return rec;
    }

    @Test
    void wellFormedEvent_isParsedAndDelegatedToInvoiceService() throws Exception {
        UUID eventId = UUID.randomUUID();
        PaymentCompletedEvent event = new PaymentCompletedEvent(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("590.00"),
                new BigDecimal("118.00"), new BigDecimal("472.00"), "UPI", Instant.parse("2024-07-15T10:00:00Z"));
        when(processedEventRepository.existsByConsumerGroupAndEventId(anyString(), eq(eventId)))
                .thenReturn(false);

        consumer.onMessage(record(eventId, mapper.writeValueAsString(event)));

        verify(invoiceService).generateForPayment(any(PaymentCompletedEvent.class));
        verify(processedEventRepository).save(any());
        verify(dlqForwarder, never()).forward(any(), any(), any(), any(), any());
    }

    @Test
    void duplicateEventId_isSkippedWithoutInvoiceGeneration() {
        UUID eventId = UUID.randomUUID();
        when(processedEventRepository.existsByConsumerGroupAndEventId(anyString(), eq(eventId)))
                .thenReturn(true);

        consumer.onMessage(record(eventId, "{}"));

        verify(invoiceService, never()).generateForPayment(any());
        verify(processedEventRepository, never()).save(any());
    }

    @Test
    void unparseablePayload_isDeadLetteredAfterRetries() {
        UUID eventId = UUID.randomUUID();
        when(processedEventRepository.existsByConsumerGroupAndEventId(anyString(), eq(eventId)))
                .thenReturn(false);

        consumer.onMessage(record(eventId, "not-json"));

        verify(invoiceService, never()).generateForPayment(any());
        verify(dlqForwarder).forward(eq(TOPIC), eq("key"), eq(eventId.toString()), eq("not-json"), anyString());
    }
}
