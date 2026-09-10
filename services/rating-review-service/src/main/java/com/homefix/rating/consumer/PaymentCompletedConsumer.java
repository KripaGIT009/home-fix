package com.homefix.rating.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homefix.rating.event.PaymentCompletedEvent;
import com.homefix.rating.service.ReviewService;
import com.homefix.shared.outbox.kafka.DlqForwarder;
import com.homefix.shared.outbox.kafka.IdempotentKafkaConsumer;
import com.homefix.shared.outbox.kafka.ProcessedEventRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code PaymentCompleted} events idempotently and opens the pair of 7-day review prompts
 * (Requirements 15.1, 15.10, 22.5). Deduplication, retry, and dead-lettering are inherited from the
 * shared {@link IdempotentKafkaConsumer} base; this class parses the payload and hands the event to
 * {@link ReviewService}.
 */
@Component
public class PaymentCompletedConsumer extends IdempotentKafkaConsumer {

    /** Consumer group; also the dedup scope for {@code ProcessedEventEntity}. */
    public static final String CONSUMER_GROUP = "rating-review-service.payment-completed";

    private static final Logger log = LoggerFactory.getLogger(PaymentCompletedConsumer.class);

    private final ObjectMapper objectMapper;
    private final ReviewService reviewService;

    public PaymentCompletedConsumer(ProcessedEventRepository processedEventRepository,
                                    DlqForwarder dlqForwarder,
                                    ObjectMapper objectMapper,
                                    ReviewService reviewService) {
        super(CONSUMER_GROUP, processedEventRepository, dlqForwarder);
        this.objectMapper = objectMapper;
        this.reviewService = reviewService;
    }

    @KafkaListener(
            topics = "${homefix.rating.topics.payment-completed:PaymentCompleted}",
            groupId = CONSUMER_GROUP)
    public void onMessage(ConsumerRecord<String, String> record) {
        consume(record);
    }

    @Override
    protected void handle(ConsumerRecord<String, String> record) {
        PaymentCompletedEvent event = parse(record.value());
        log.debug("Opening review prompts for booking {} (payment {})",
                event.bookingId(), event.paymentId());
        reviewService.openReviewPrompts(event);
    }

    private PaymentCompletedEvent parse(String json) {
        try {
            return objectMapper.readValue(json, PaymentCompletedEvent.class);
        } catch (Exception e) {
            // Non-recoverable: a malformed payload will never parse. Throwing routes it to the DLQ
            // after the base class exhausts retries.
            throw new IllegalStateException("Unparseable PaymentCompleted payload", e);
        }
    }
}
