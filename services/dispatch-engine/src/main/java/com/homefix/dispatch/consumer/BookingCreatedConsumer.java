package com.homefix.dispatch.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homefix.dispatch.domain.DispatchRequest;
import com.homefix.dispatch.event.BookingCreatedEvent;
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
 * {@link IdempotentKafkaConsumer} base; this class only parses the payload and hands the resulting
 * {@link DispatchRequest} to the bulkhead executor, which routes it to the emergency or scheduled
 * pool (Requirement 24.5).
 *
 * <p>The actual matching runs asynchronously on a bulkhead pool so the Kafka listener thread is
 * freed immediately and a slow dispatch cannot back up the consumer.
 */
@Component
public class BookingCreatedConsumer extends IdempotentKafkaConsumer {

    /** Consumer group; also the dedup scope for {@code ProcessedEventEntity}. */
    public static final String CONSUMER_GROUP = "dispatch-engine.booking-created";

    private static final Logger log = LoggerFactory.getLogger(BookingCreatedConsumer.class);

    private final ObjectMapper objectMapper;
    private final BulkheadDispatchExecutor bulkheadDispatchExecutor;

    public BookingCreatedConsumer(ProcessedEventRepository processedEventRepository,
                                  DlqForwarder dlqForwarder,
                                  ObjectMapper objectMapper,
                                  BulkheadDispatchExecutor bulkheadDispatchExecutor) {
        super(CONSUMER_GROUP, processedEventRepository, dlqForwarder);
        this.objectMapper = objectMapper;
        this.bulkheadDispatchExecutor = bulkheadDispatchExecutor;
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
        // coordinates or skill tags, so these fields arrive absent. Matching on absent values is
        // worse than not matching at all: with primitives they defaulted to 0.0 and every candidate
        // was scored against the Gulf of Guinea, silently producing a plausible-looking but wrong
        // assignment. Refuse instead, so the record dead-letters with a reason an operator can act
        // on. Completing this needs an enrichment step that resolves the address to coordinates and
        // the subcategory to its skill tags; see docs/API_CONTRACTS.md, BookingCreated.
        if (event.requiresEnrichment()) {
            throw new UnsupportedOperationException(
                    "BookingCreated for booking " + event.bookingId()
                            + " carries no customer coordinates or required skill tags, so no"
                            + " provider match is possible. The Dispatch Engine must resolve"
                            + " addressId=" + event.addressId()
                            + " and subcategoryId=" + event.subcategoryId()
                            + " before matching; dispatching on absent values is refused.");
        }

        DispatchRequest request = new DispatchRequest(
                event.bookingId(),
                event.customerId(),
                event.customerLat(),
                event.customerLon(),
                event.subcategoryId(),
                event.requiredSkillTags(),
                event.emergency(),
                event.occurredAt());
        log.debug("Dispatching {} booking {}", event.emergency() ? "emergency" : "scheduled",
                event.bookingId());
        bulkheadDispatchExecutor.submit(request);
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
