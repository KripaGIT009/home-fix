package com.homefix.dispatch.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.homefix.dispatch.domain.OfferStatus;
import com.homefix.dispatch.service.JobOfferService;
import com.homefix.dispatch.service.fake.FlagCancellation;
import com.homefix.dispatch.service.fake.InMemoryJobOfferStore;
import com.homefix.dispatch.service.fake.MutableClock;
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
 * Tests {@link BookingCancelledConsumer} with the shared consumer base's real dedup and
 * dead-letter logic, an in-memory cancellation flag and the real {@link JobOfferService}
 * (Requirement 8.7): a cancellation flags the booking and withdraws a still-pending offer, leaves a
 * decided offer alone, and is safe to receive for a booking dispatch never offered; a payload
 * without a bookingId is dead-lettered.
 */
@ExtendWith(MockitoExtension.class)
class BookingCancelledConsumerTest {

    private static final String TOPIC = "BookingCancelled";

    @Mock
    private ProcessedEventRepository processedEventRepository;
    @Mock
    private DlqForwarder dlqForwarder;

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final FlagCancellation cancellation = new FlagCancellation();
    private JobOfferService offers;
    private BookingCancelledConsumer consumer;

    private final UUID bookingId = UUID.randomUUID();
    private final UUID provider = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        offers = new JobOfferService(new InMemoryJobOfferStore(),
                new MutableClock(Instant.parse("2026-10-02T10:00:00Z")));
        consumer = new BookingCancelledConsumer(processedEventRepository, dlqForwarder, mapper,
                cancellation, offers);
    }

    private ConsumerRecord<String, String> record(UUID eventId, String value) {
        ConsumerRecord<String, String> rec = new ConsumerRecord<>(TOPIC, 0, 0L, "key", value);
        rec.headers().add(new RecordHeader(KafkaProducerTemplate.HEADER_EVENT_ID,
                eventId.toString().getBytes(StandardCharsets.UTF_8)));
        return rec;
    }

    private UUID newEvent() {
        UUID eventId = UUID.randomUUID();
        when(processedEventRepository.existsByConsumerGroupAndEventId(anyString(), eq(eventId)))
                .thenReturn(false);
        return eventId;
    }

    /** The Booking Service's payload, including the fields this consumer ignores. */
    private String payload(UUID booking) {
        return """
                {"bookingId":%s,"reference":"HFX-2026-0000009","customerId":"%s","providerId":null,
                 "previousStatus":"SEARCHING","status":"CANCELLED","cancelledBy":"%s",
                 "cancelledByRole":"CUSTOMER","reason":"changed my mind","cancellationFee":0,
                 "bookingCreatedAt":"2026-10-02T09:55:00Z","occurredAt":"2026-10-02T10:00:10Z"}"""
                .formatted(booking == null ? "null" : "\"" + booking + "\"",
                        UUID.randomUUID(), UUID.randomUUID());
    }

    @Test
    void cancellation_flagsTheBookingAndWithdrawsItsPendingOffer() {
        offers.open(bookingId, provider, Duration.ofSeconds(60));

        consumer.onMessage(record(newEvent(), payload(bookingId)));

        assertThat(cancellation.isCancelled(bookingId)).isTrue();
        assertThat(offers.statusOf(bookingId, provider)).contains(OfferStatus.WITHDRAWN);
        verify(processedEventRepository).save(any());
        verifyNoInteractions(dlqForwarder);
    }

    @Test
    void cancellationAfterTheProviderAccepted_leavesTheAcceptStanding() {
        offers.open(bookingId, provider, Duration.ofSeconds(60));
        offers.decide(bookingId, provider, OfferStatus.ACCEPTED);

        consumer.onMessage(record(newEvent(), payload(bookingId)));

        assertThat(cancellation.isCancelled(bookingId)).isTrue();
        assertThat(offers.statusOf(bookingId, provider)).contains(OfferStatus.ACCEPTED);
    }

    @Test
    void cancellationOfABookingNeverOffered_onlyFlagsIt() {
        consumer.onMessage(record(newEvent(), payload(bookingId)));

        assertThat(cancellation.isCancelled(bookingId)).isTrue();
        verify(processedEventRepository).save(any());
        verifyNoInteractions(dlqForwarder);
    }

    @Test
    void duplicateEvent_isSkipped() {
        UUID eventId = UUID.randomUUID();
        when(processedEventRepository.existsByConsumerGroupAndEventId(anyString(), eq(eventId)))
                .thenReturn(true);

        consumer.onMessage(record(eventId, payload(bookingId)));

        assertThat(cancellation.isCancelled(bookingId)).isFalse();
    }

    @Test
    void payloadWithoutBookingId_isDeadLettered() {
        UUID eventId = newEvent();
        String value = payload(null);

        consumer.onMessage(record(eventId, value));

        verify(dlqForwarder).forward(eq(TOPIC), eq("key"), eq(eventId.toString()), eq(value), anyString());
        verify(processedEventRepository, never()).save(any());
    }

    @Test
    void unparseablePayload_isDeadLettered() {
        UUID eventId = newEvent();

        consumer.onMessage(record(eventId, "not-json"));

        verify(dlqForwarder).forward(eq(TOPIC), eq("key"), eq(eventId.toString()), eq("not-json"), anyString());
        assertThat(cancellation.isCancelled(bookingId)).isFalse();
    }
}
