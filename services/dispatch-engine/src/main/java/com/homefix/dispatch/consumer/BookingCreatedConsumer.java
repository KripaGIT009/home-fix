package com.homefix.dispatch.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homefix.dispatch.domain.BookingNotSearchableException;
import com.homefix.dispatch.domain.DispatchRequest;
import com.homefix.dispatch.domain.UnresolvableBookingException;
import com.homefix.dispatch.event.BookingCreatedEvent;
import com.homefix.dispatch.port.BookingTransitionPort;
import com.homefix.dispatch.service.BookingEnrichmentService;
import com.homefix.dispatch.service.BulkheadDispatchExecutor;
import com.homefix.shared.outbox.kafka.DlqForwarder;
import com.homefix.shared.outbox.kafka.IdempotentKafkaConsumer;
import com.homefix.shared.outbox.kafka.ProcessedEventRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code BookingCreated} events idempotently and kicks off provider matching
 * (Requirements 8.2, 22.5). Deduplication, retry, and dead-lettering are inherited from the shared
 * {@link IdempotentKafkaConsumer} base; this class parses the payload, has the
 * {@link BookingEnrichmentService} resolve the customer coordinates and required skill tags the
 * event does not carry, and hands the resulting {@link DispatchRequest} to the bulkhead executor,
 * which routes it to the emergency or scheduled pool (Requirement 24.5).
 *
 * <p>Enrichment runs on the listener thread, bounded by the outbound adapters' 5 s timeouts, so a
 * transient failure is retried and dead-lettered by the base class. A booking that can never be
 * matched as published is different: retrying cannot help, and dead-lettering it alone left the
 * booking in SEARCHING_PROVIDER forever with the customer watching a spinner. It is moved to
 * SEARCHING_FAILED instead, which ends the booking visibly and lets the customer book again. The actual matching then runs
 * asynchronously on a bulkhead pool so the Kafka listener thread is freed and a slow dispatch
 * cannot back up the consumer.
 */
@Component
public class BookingCreatedConsumer extends IdempotentKafkaConsumer {

    /** Consumer group; also the dedup scope for {@code ProcessedEventEntity}. */
    public static final String CONSUMER_GROUP = "dispatch-engine.booking-created";

    private static final Logger log = LoggerFactory.getLogger(BookingCreatedConsumer.class);

    private final ObjectMapper objectMapper;
    private final BookingEnrichmentService enrichmentService;
    private final BulkheadDispatchExecutor bulkheadDispatchExecutor;
    private final BookingTransitionPort bookingTransition;

    public BookingCreatedConsumer(ProcessedEventRepository processedEventRepository,
                                  DlqForwarder dlqForwarder,
                                  ObjectMapper objectMapper,
                                  BookingEnrichmentService enrichmentService,
                                  BulkheadDispatchExecutor bulkheadDispatchExecutor,
                                  BookingTransitionPort bookingTransition) {
        super(CONSUMER_GROUP, processedEventRepository, dlqForwarder);
        this.objectMapper = objectMapper;
        this.enrichmentService = enrichmentService;
        this.bulkheadDispatchExecutor = bulkheadDispatchExecutor;
        this.bookingTransition = bookingTransition;
    }

    @KafkaListener(
            topics = "${homefix.dispatch.topics.booking-created:BookingCreated}",
            groupId = CONSUMER_GROUP)
    public void onMessage(ConsumerRecord<String, String> record) {
        consume(record);
    }

    @Override
    protected void handle(ConsumerRecord<String, String> record) {
        BookingCreatedEvent event = parse(record.value());

        // The Booking Service publishes booking facts (addressId, subcategoryId), not denormalised
        // coordinates or skill tags. Resolve them here, synchronously and before the bulkhead
        // hand-off, so a failure is handled on this thread instead of being lost inside a pool
        // task. An unavailable dependency throws EnrichmentUnavailableException, which is not
        // caught: it is retried, then dead-lettered for replay. A booking that can never be matched
        // as published throws UnresolvableBookingException and is failed below. Dispatching on
        // absent values is refused either way, since they once defaulted to 0.0 and every
        // candidate was scored against the Gulf of Guinea. See docs/API_CONTRACTS.md,
        // BookingCreated.
        DispatchRequest request;
        try {
            request = enrichmentService.enrich(event);
        } catch (UnresolvableBookingException unresolvable) {
            failUnmatchable(event, unresolvable);
            return;
        }

        log.debug("Dispatching {} booking {}", request.emergency() ? "emergency" : "scheduled",
                request.bookingId());
        bulkheadDispatchExecutor.submit(request);
    }

    /**
     * Ends a booking that can never be dispatched by moving it to SEARCHING_FAILED. The Booking
     * Service treats a repeat as a no-op, so a redelivered event is harmless. If the Booking Service
     * cannot be reached the transition failure propagates and the event is retried like any other
     * transient failure; if the booking has meanwhile left SEARCHING_PROVIDER (cancelled) there is
     * nothing left to do.
     */
    private void failUnmatchable(BookingCreatedEvent event, UnresolvableBookingException reason) {
        if (event.bookingId() == null) {
            throw reason;
        }
        log.warn("Booking {} cannot be dispatched; marking it SEARCHING_FAILED. Reason: {}",
                event.bookingId(), reason.getMessage());
        try {
            bookingTransition.markSearchingFailed(event.bookingId());
        } catch (BookingNotSearchableException alreadyEnded) {
            log.info("Booking {} had already left the search; nothing to fail", event.bookingId());
        }
    }

    private BookingCreatedEvent parse(String json) {
        try {
            return objectMapper.readValue(json, BookingCreatedEvent.class);
        } catch (Exception e) {
            // Non-recoverable: a malformed payload will never parse. Throwing routes it to the DLQ
            // after the base class exhausts retries.
            throw new IllegalStateException("Unparseable BookingCreated payload", e);
        }
    }
}
