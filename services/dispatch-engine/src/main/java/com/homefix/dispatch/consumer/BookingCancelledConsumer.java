package com.homefix.dispatch.consumer;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.homefix.dispatch.port.BookingCancellationPort;
import com.homefix.dispatch.service.JobOfferService;
import com.homefix.shared.outbox.kafka.DlqForwarder;
import com.homefix.shared.outbox.kafka.IdempotentKafkaConsumer;
import com.homefix.shared.outbox.kafka.ProcessedEventRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Consumes {@code BookingCancelled} so an in-flight search stops when its booking does
 * (Requirement 8.7). The Booking Service publishes it for every transition that ends a booking
 * before the job is done — a customer or staff cancellation, and SEARCHING_FAILED — and either way
 * the booking no longer needs a provider. Two effects, both idempotent:
 * <ul>
 *   <li>the booking is flagged cancelled, which the dispatch loop checks before every offer, so no
 *       further provider is offered the job and no further ProviderRejected is published;</li>
 *   <li>a still-pending offer for the booking is withdrawn, so the provider currently looking at it
 *       can no longer accept it.</li>
 * </ul>
 */
@Component
public class BookingCancelledConsumer extends IdempotentKafkaConsumer {

    /** Consumer group; also the dedup scope for {@code ProcessedEventEntity}. */
    public static final String CONSUMER_GROUP = "dispatch-engine.booking-cancelled";

    private static final Logger log = LoggerFactory.getLogger(BookingCancelledConsumer.class);

    private final ObjectMapper objectMapper;
    private final BookingCancellationPort cancellation;
    private final JobOfferService jobOffers;

    public BookingCancelledConsumer(ProcessedEventRepository processedEventRepository,
                                    DlqForwarder dlqForwarder,
                                    ObjectMapper objectMapper,
                                    BookingCancellationPort cancellation,
                                    JobOfferService jobOffers) {
        super(CONSUMER_GROUP, processedEventRepository, dlqForwarder);
        this.objectMapper = objectMapper;
        this.cancellation = cancellation;
        this.jobOffers = jobOffers;
    }

    @KafkaListener(
            topics = "${homefix.dispatch.topics.booking-cancelled:BookingCancelled}",
            groupId = CONSUMER_GROUP)
    public void onMessage(ConsumerRecord<String, String> record) {
        consume(record);
    }

    @Override
    protected void handle(ConsumerRecord<String, String> record) {
        UUID bookingId = parse(record.value()).bookingId();
        if (bookingId == null) {
            throw new IllegalStateException("BookingCancelled payload is missing bookingId");
        }
        cancellation.markCancelled(bookingId);
        jobOffers.withdraw(bookingId);
        log.debug("Booking {} left the search; dispatch will stop offering it", bookingId);
    }

    private Payload parse(String json) {
        try {
            return objectMapper.readValue(json, Payload.class);
        } catch (Exception e) {
            throw new IllegalStateException("Unparseable BookingCancelled payload", e);
        }
    }

    /** The only field this consumer needs; the producer's richer payload is otherwise ignored. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Payload(UUID bookingId) {
    }
}
