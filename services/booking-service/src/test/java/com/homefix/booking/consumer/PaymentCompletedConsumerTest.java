package com.homefix.booking.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.homefix.booking.domain.Booking;
import com.homefix.booking.domain.BookingStateMachine;
import com.homefix.booking.domain.BookingStatus;
import com.homefix.booking.event.PaymentCompletedEvent;
import com.homefix.booking.service.BookingLifecycleEventPublisher;
import com.homefix.booking.service.BookingPaymentService;
import com.homefix.booking.service.BookingTransitionService;
import com.homefix.booking.support.InMemoryBookingAuditRepository;
import com.homefix.booking.support.InMemoryBookingRepository;
import com.homefix.shared.outbox.OutboxEventPublisher;
import com.homefix.shared.outbox.kafka.DlqForwarder;
import com.homefix.shared.outbox.kafka.IdempotentKafkaConsumer;
import com.homefix.shared.outbox.kafka.KafkaProducerTemplate;
import com.homefix.shared.outbox.kafka.ProcessedEventRepository;

/**
 * Tests {@link PaymentCompletedConsumer} (Requirements 12.6, 22.5, 22.6): a payment settles the
 * booking; a booking that is already paid, cannot be paid, or does not exist is acknowledged rather
 * than retried or dead-lettered; an unreadable payload is dead-lettered; and a failed attempt — a lost
 * optimistic-lock race — is retried without recording the event as processed.
 *
 * <p>The consumer is constructed by hand, as the shared base class's own tests do, so the attempts run
 * back to back; the real {@link BookingPaymentService}, state machine and audit trail sit behind it.
 */
class PaymentCompletedConsumerTest {

    private static final String TOPIC = "PaymentCompleted";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-03T09:30:00Z"), ZoneOffset.UTC);

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private ProcessedEventRepository processedEventRepository;
    private DlqForwarder dlqForwarder;
    private InMemoryBookingRepository bookings;
    private InMemoryBookingAuditRepository audits;
    private PaymentCompletedConsumer consumer;

    @BeforeEach
    void setUp() {
        processedEventRepository = mock(ProcessedEventRepository.class);
        dlqForwarder = mock(DlqForwarder.class);
        bookings = new InMemoryBookingRepository();
        audits = new InMemoryBookingAuditRepository();
        consumer = consumerOver(bookings);
    }

    private PaymentCompletedConsumer consumerOver(InMemoryBookingRepository repository) {
        BookingPaymentService service = new BookingPaymentService(repository,
                new BookingTransitionService(new BookingStateMachine(), audits,
                        new BookingLifecycleEventPublisher(mock(OutboxEventPublisher.class), CLOCK), CLOCK),
                mock(PlatformTransactionManager.class));
        return new PaymentCompletedConsumer(processedEventRepository, dlqForwarder, mapper, service);
    }

    private Booking booking(BookingStatus status) {
        Booking b = Booking.create("HFX-20261003-PAY001", UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), false, Instant.now(CLOCK), new BigDecimal("590.00"));
        b.setProviderId(UUID.randomUUID());
        b.applyStatus(status);
        return bookings.save(b);
    }

    private String payload(UUID bookingId) throws Exception {
        return mapper.writeValueAsString(new PaymentCompletedEvent(UUID.randomUUID(), bookingId,
                UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("590.00"), new BigDecimal("118.00"),
                new BigDecimal("472.00"), "UPI", Instant.parse("2026-10-03T09:29:00Z")));
    }

    private ConsumerRecord<String, String> record(UUID eventId, String value) {
        ConsumerRecord<String, String> rec = new ConsumerRecord<>(TOPIC, 0, 0L, "key", value);
        rec.headers().add(new RecordHeader(KafkaProducerTemplate.HEADER_EVENT_ID,
                eventId.toString().getBytes(StandardCharsets.UTF_8)));
        return rec;
    }

    private void assertAcknowledged() {
        verify(processedEventRepository).save(any());
        verify(dlqForwarder, never()).forward(any(), any(), any(), any(), any());
    }

    @Test
    void usesTheContractedConsumerGroup() {
        assertThat(PaymentCompletedConsumer.CONSUMER_GROUP).isEqualTo("booking-service.payment-completed");
    }

    @Test
    void paymentCompleted_settlesAPendingBooking() throws Exception {
        Booking b = booking(BookingStatus.PAYMENT_PENDING);

        consumer.onMessage(record(UUID.randomUUID(), payload(b.getId())));

        assertThat(b.getStatus()).isEqualTo(BookingStatus.PAYMENT_COMPLETED);
        assertAcknowledged();
    }

    @Test
    void paymentCompleted_overtakingThePendingCall_stillSettlesTheBooking() throws Exception {
        Booking b = booking(BookingStatus.JOB_COMPLETED);

        consumer.onMessage(record(UUID.randomUUID(), payload(b.getId())));

        assertThat(b.getStatus()).isEqualTo(BookingStatus.PAYMENT_COMPLETED);
        assertThat(audits.byBooking(b.getId())).hasSize(3);
        assertAcknowledged();
    }

    @Test
    void alreadyPaid_isAcknowledgedWithoutAnotherTransition() throws Exception {
        Booking b = booking(BookingStatus.PAYMENT_COMPLETED);

        consumer.onMessage(record(UUID.randomUUID(), payload(b.getId())));

        assertThat(b.getStatus()).isEqualTo(BookingStatus.PAYMENT_COMPLETED);
        assertThat(audits.all()).isEmpty();
        assertAcknowledged();
    }

    @Test
    void bookingInAStateAPaymentCannotSettle_isAcknowledgedNotRetried() throws Exception {
        Booking b = booking(BookingStatus.CANCELLED);

        consumer.onMessage(record(UUID.randomUUID(), payload(b.getId())));

        assertThat(b.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(audits.all()).isEmpty();
        assertAcknowledged();
    }

    @Test
    void disputedBooking_isAcknowledgedNotSettled() throws Exception {
        Booking b = booking(BookingStatus.DISPUTED);

        consumer.onMessage(record(UUID.randomUUID(), payload(b.getId())));

        assertThat(b.getStatus()).isEqualTo(BookingStatus.DISPUTED);
        assertAcknowledged();
    }

    @Test
    void unknownBooking_isAcknowledged() throws Exception {
        consumer.onMessage(record(UUID.randomUUID(), payload(UUID.randomUUID())));

        assertAcknowledged();
    }

    @Test
    void duplicateEvent_isSkipped() throws Exception {
        Booking b = booking(BookingStatus.PAYMENT_PENDING);
        UUID eventId = UUID.randomUUID();
        when(processedEventRepository.existsByConsumerGroupAndEventId(
                PaymentCompletedConsumer.CONSUMER_GROUP, eventId)).thenReturn(true);

        consumer.onMessage(record(eventId, payload(b.getId())));

        assertThat(b.getStatus()).isEqualTo(BookingStatus.PAYMENT_PENDING);
        verify(processedEventRepository, never()).save(any());
    }

    @Test
    void unparseablePayload_isDeadLetteredAndNeverRecorded() {
        UUID eventId = UUID.randomUUID();

        consumer.onMessage(record(eventId, "not-json"));

        verify(dlqForwarder).forward(eq(TOPIC), eq("key"), eq(eventId.toString()), eq("not-json"), anyString());
        verify(processedEventRepository, never()).save(any());
    }

    @Test
    void payloadWithoutABookingId_isDeadLettered() {
        UUID eventId = UUID.randomUUID();

        consumer.onMessage(record(eventId, "{\"paymentId\":\"" + UUID.randomUUID() + "\"}"));

        verify(dlqForwarder).forward(eq(TOPIC), eq("key"), eq(eventId.toString()), anyString(), anyString());
        verify(processedEventRepository, never()).save(any());
    }

    @Test
    void lostOptimisticLock_isRetriedAndOnlyTheSuccessfulAttemptIsRecorded() throws Exception {
        Booking b = booking(BookingStatus.PAYMENT_PENDING);
        PaymentCompletedConsumer racing = consumerOver(new InMemoryBookingRepository() {
            private int reads;

            @Override
            public Optional<Booking> findById(UUID id) {
                if (++reads == 1) {
                    throw new ObjectOptimisticLockingFailureException(Booking.class, id);
                }
                return bookings.findById(id);
            }
        });

        racing.onMessage(record(UUID.randomUUID(), payload(b.getId())));

        assertThat(b.getStatus()).isEqualTo(BookingStatus.PAYMENT_COMPLETED);
        verify(processedEventRepository, times(1)).save(any());
        verify(dlqForwarder, never()).forward(any(), any(), any(), any(), any());
    }

    @Test
    void failedAttemptsInATransaction_rollBackTheDedupRowWithTheHandler() throws Exception {
        Booking b = booking(BookingStatus.PAYMENT_PENDING);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        PaymentCompletedConsumer failing = consumerOver(new InMemoryBookingRepository() {
            @Override
            public Optional<Booking> findById(UUID id) {
                throw new ObjectOptimisticLockingFailureException(Booking.class, id);
            }
        });
        failing.setTransactionManager(transactionManager);
        UUID eventId = UUID.randomUUID();

        failing.onMessage(record(eventId, payload(b.getId())));

        // Each attempt claimed the event inside its transaction, and each rolled back with the
        // failure, so nothing marks the event processed; exhausted, it is dead-lettered.
        verify(processedEventRepository, times(IdempotentKafkaConsumer.MAX_RETRIES)).saveAndFlush(any());
        verify(transactionManager, times(IdempotentKafkaConsumer.MAX_RETRIES)).rollback(any());
        verify(transactionManager, never()).commit(any());
        verify(dlqForwarder).forward(eq(TOPIC), eq("key"), eq(eventId.toString()), anyString(), anyString());
        assertThat(b.getStatus()).isEqualTo(BookingStatus.PAYMENT_PENDING);
    }
}
