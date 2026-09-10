package com.homefix.dispatch.consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homefix.dispatch.domain.DispatchRequest;
import com.homefix.dispatch.event.BookingCreatedEvent;
import com.homefix.dispatch.service.BulkheadDispatchExecutor;
import com.homefix.shared.outbox.kafka.DlqForwarder;
import com.homefix.shared.outbox.kafka.KafkaProducerTemplate;
import com.homefix.shared.outbox.kafka.ProcessedEventRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Tests {@link BookingCreatedConsumer}: a well-formed event is parsed into a {@link DispatchRequest}
 * and submitted to the bulkhead executor (Requirement 8.2); a duplicate event id is skipped
 * (Requirement 22.5); an unparseable payload is dead-lettered after retries (Requirement 22.6).
 */
@ExtendWith(MockitoExtension.class)
class BookingCreatedConsumerTest {

    private static final String TOPIC = "BookingCreated";

    @Mock
    private ProcessedEventRepository processedEventRepository;
    @Mock
    private DlqForwarder dlqForwarder;
    @Mock
    private BulkheadDispatchExecutor executor;

    private final ObjectMapper mapper = new ObjectMapper();

    private BookingCreatedConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new BookingCreatedConsumer(processedEventRepository, dlqForwarder, mapper, executor);
    }

    private ConsumerRecord<String, String> record(UUID eventId, String value) {
        ConsumerRecord<String, String> rec = new ConsumerRecord<>(TOPIC, 0, 0L, "key", value);
        rec.headers().add(new RecordHeader(KafkaProducerTemplate.HEADER_EVENT_ID,
                eventId.toString().getBytes(StandardCharsets.UTF_8)));
        return rec;
    }

    @Test
    void wellFormedEmergencyEvent_submitsDispatchRequestPreservingEmergencyFlag() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID booking = UUID.randomUUID();
        BookingCreatedEvent event = new BookingCreatedEvent(booking, UUID.randomUUID(),
                UUID.randomUUID(), 12.9, 77.6, List.of("plumbing"), true);
        when(processedEventRepository.existsByConsumerGroupAndEventId(anyString(), eq(eventId)))
                .thenReturn(false);

        consumer.onMessage(record(eventId, mapper.writeValueAsString(event)));

        ArgumentCaptor<DispatchRequest> captor = ArgumentCaptor.forClass(DispatchRequest.class);
        verify(executor).submit(captor.capture());
        DispatchRequest request = captor.getValue();
        org.assertj.core.api.Assertions.assertThat(request.bookingId()).isEqualTo(booking);
        org.assertj.core.api.Assertions.assertThat(request.emergency()).isTrue();
        verify(processedEventRepository).save(any());
    }

    @Test
    void duplicateEventId_isSkipped() {
        UUID eventId = UUID.randomUUID();
        when(processedEventRepository.existsByConsumerGroupAndEventId(anyString(), eq(eventId)))
                .thenReturn(true);

        consumer.onMessage(record(eventId, "{}"));

        verify(executor, never()).submit(any());
    }

    @Test
    void unparseablePayload_isDeadLettered() {
        UUID eventId = UUID.randomUUID();
        when(processedEventRepository.existsByConsumerGroupAndEventId(anyString(), eq(eventId)))
                .thenReturn(false);

        consumer.onMessage(record(eventId, "not-json"));

        verify(executor, never()).submit(any());
        verify(dlqForwarder).forward(eq(TOPIC), eq("key"), eq(eventId.toString()), eq("not-json"), anyString());
    }
}
