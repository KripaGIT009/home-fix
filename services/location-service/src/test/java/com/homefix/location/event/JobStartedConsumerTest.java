package com.homefix.location.event;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homefix.location.service.LocationService;
import com.homefix.shared.outbox.kafka.DlqForwarder;
import com.homefix.shared.outbox.kafka.KafkaProducerTemplate;
import com.homefix.shared.outbox.kafka.ProcessedEventRepository;

/**
 * Unit tests for {@link JobStartedConsumer}: a JobStarted event terminates the Booking's feed
 * (Requirement 10.5), a duplicate is skipped (idempotency, Task 6), and a malformed payload is
 * dead-lettered rather than terminating the wrong feed.
 */
class JobStartedConsumerTest {

    private ProcessedEventRepository processedEvents;
    private DlqForwarder dlqForwarder;
    private LocationService locationService;
    private JobStartedConsumer consumer;

    @BeforeEach
    void setUp() {
        processedEvents = mock(ProcessedEventRepository.class);
        dlqForwarder = mock(DlqForwarder.class);
        locationService = mock(LocationService.class);
        consumer = new JobStartedConsumer(processedEvents, dlqForwarder, locationService,
                new ObjectMapper());
    }

    private ConsumerRecord<String, String> record(UUID eventId, String payload) {
        ConsumerRecord<String, String> record =
                new ConsumerRecord<>(JobStartedConsumer.DEFAULT_TOPIC, 0, 0L, "key", payload);
        if (eventId != null) {
            record.headers().add(new RecordHeader(KafkaProducerTemplate.HEADER_EVENT_ID,
                    eventId.toString().getBytes(StandardCharsets.UTF_8)));
        }
        return record;
    }

    @Test
    void terminatesFeedForBookingInEvent() {
        UUID bookingId = UUID.randomUUID();
        when(processedEvents.existsByConsumerGroupAndEventId(anyString(), any())).thenReturn(false);

        consumer.onMessage(record(UUID.randomUUID(), "{\"bookingId\":\"" + bookingId + "\"}"));

        verify(locationService, times(1)).terminate(bookingId);
    }

    @Test
    void skipsDuplicateEvent() {
        when(processedEvents.existsByConsumerGroupAndEventId(anyString(), any())).thenReturn(true);

        consumer.onMessage(record(UUID.randomUUID(),
                "{\"bookingId\":\"" + UUID.randomUUID() + "\"}"));

        verify(locationService, never()).terminate(any());
    }

    @Test
    void malformedPayloadIsDeadLetteredWithoutTerminating() {
        when(processedEvents.existsByConsumerGroupAndEventId(anyString(), any())).thenReturn(false);

        consumer.onMessage(record(UUID.randomUUID(), "{not-json"));

        verify(locationService, never()).terminate(any());
        verify(dlqForwarder, times(1))
                .forward(eq(JobStartedConsumer.DEFAULT_TOPIC), eq("key"), anyString(), anyString(), anyString());
    }
}
