package com.homefix.booking.consumer;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homefix.booking.event.PaymentCompletedEvent;
import com.homefix.booking.service.BookingPaymentService;
import com.homefix.booking.service.BookingPaymentService.CompletionOutcome;
import com.homefix.shared.outbox.kafka.DlqForwarder;
import com.homefix.shared.outbox.kafka.IdempotentKafkaConsumer;
import com.homefix.shared.outbox.kafka.ProcessedEventRepository;

/**
 * Consumes {@code PaymentCompleted} events idempotently and settles the booking at
 * PAYMENT_COMPLETED (Requirements 12.6, 9.1, 22.5). Without it a paid booking stayed
 * PAYMENT_PENDING (or JOB_COMPLETED) forever, because this service consumed no events at all.
 *
 * <p>Deduplication, retry and dead-lettering are inherited from the shared
 * {@link IdempotentKafkaConsumer}: {@link #handle} runs in one transaction with the
 * {@code processed_event} insert, so an attempt that fails — a lost optimistic-lock race with the
 * payment-pending call, a database outage — rolls back its dedup row too and is redelivered.
 *
 * <p>What is <em>not</em> a failure: a booking that cannot be settled (cancelled, disputed,
 * refunded...) or that does not exist. Retrying cannot change either answer and dead-lettering would
 * only page someone about a fact already recorded by the Payment Service, so both are logged at WARN
 * and acknowledged. Only a payload that cannot be read at all goes the retry-then-DLT way.
 */
@Component
public class PaymentCompletedConsumer extends IdempotentKafkaConsumer {

    /** Consumer group; also the dedup scope for {@code ProcessedEventEntity}. */
    public static final String CONSUMER_GROUP = "booking-service.payment-completed";

    private static final Logger log = LoggerFactory.getLogger(PaymentCompletedConsumer.class);

    private final ObjectMapper objectMapper;
    private final BookingPaymentService bookingPaymentService;

    public PaymentCompletedConsumer(ProcessedEventRepository processedEventRepository,
                                    DlqForwarder dlqForwarder,
                                    ObjectMapper objectMapper,
                                    BookingPaymentService bookingPaymentService) {
        super(CONSUMER_GROUP, processedEventRepository, dlqForwarder);
        this.objectMapper = objectMapper;
        this.bookingPaymentService = bookingPaymentService;
    }

    @KafkaListener(
            topics = "${homefix.booking.topics.payment-completed:PaymentCompleted}",
            groupId = CONSUMER_GROUP)
    public void onMessage(ConsumerRecord<String, String> record) {
        consume(record);
    }

    @Override
    protected void handle(ConsumerRecord<String, String> record) {
        PaymentCompletedEvent event = parse(record.value());
        CompletionOutcome outcome = bookingPaymentService.markPaymentCompleted(event.bookingId(), event.paymentId());
        switch (outcome) {
            case COMPLETED -> log.debug("Settled booking {} for payment {}", event.bookingId(), event.paymentId());
            case ALREADY_COMPLETED -> log.debug("Booking {} already PAYMENT_COMPLETED; payment {} ignored",
                    event.bookingId(), event.paymentId());
            // Already logged at WARN, with the booking's state, by the service; acknowledged here.
            case NOT_PAYABLE, UNKNOWN_BOOKING -> log.debug("Acknowledging PaymentCompleted for payment {} without"
                    + " a transition ({})", event.paymentId(), outcome);
        }
    }

    private PaymentCompletedEvent parse(String json) {
        PaymentCompletedEvent event;
        try {
            event = objectMapper.readValue(json, PaymentCompletedEvent.class);
        } catch (Exception e) {
            // Non-recoverable: a malformed payload will never parse. Throwing routes it to the DLQ
            // after the base class exhausts retries.
            throw new IllegalStateException("Unparseable PaymentCompleted payload", e);
        }
        if (event == null || event.bookingId() == null) {
            throw new IllegalStateException("PaymentCompleted payload carries no bookingId");
        }
        return event;
    }
}
