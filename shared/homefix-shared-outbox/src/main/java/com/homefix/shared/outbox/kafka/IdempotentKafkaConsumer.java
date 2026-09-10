package com.homefix.shared.outbox.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Base class for Kafka consumers that must process each event exactly once and route poison
 * messages to a dead-letter topic (Task 6, Requirements 22.5 and 22.6).
 *
 * <p>Processing flow for each record:
 * <ol>
 *   <li>Extract the stable {@code eventId} header.</li>
 *   <li>If this consumer group has already persisted that {@code eventId}, skip the record
 *       (duplicate redelivery) — Requirement 22.5.</li>
 *   <li>Otherwise invoke {@link #handle(ConsumerRecord)}. On success, persist the
 *       {@code eventId} so future redeliveries are skipped.</li>
 *   <li>On failure, retry up to {@value #MAX_RETRIES} times with a {@value #RETRY_DELAY_MS} ms
 *       delay between attempts. If all attempts fail, forward the record to the dead-letter
 *       topic and continue — Requirement 22.6.</li>
 * </ol>
 *
 * <p>Subclasses implement only {@link #handle(ConsumerRecord)} with their business logic and
 * supply their consumer-group name via the constructor.
 */
public abstract class IdempotentKafkaConsumer {

    /** Number of processing attempts after which the event is dead-lettered (Requirement 22.6). */
    public static final int MAX_RETRIES = 3;
    /** Delay between retry attempts, in milliseconds (Requirement 22.6). */
    public static final long RETRY_DELAY_MS = 5_000L;

    private static final Logger log = LoggerFactory.getLogger(IdempotentKafkaConsumer.class);

    private final String consumerGroup;
    private final ProcessedEventRepository processedEventRepository;
    private final DlqForwarder dlqForwarder;
    private final Sleeper sleeper;

    protected IdempotentKafkaConsumer(String consumerGroup,
                                      ProcessedEventRepository processedEventRepository,
                                      DlqForwarder dlqForwarder) {
        this(consumerGroup, processedEventRepository, dlqForwarder, Thread::sleep);
    }

    // Visible for testing so unit tests are not slowed by real 5 s sleeps.
    IdempotentKafkaConsumer(String consumerGroup,
                            ProcessedEventRepository processedEventRepository,
                            DlqForwarder dlqForwarder,
                            Sleeper sleeper) {
        this.consumerGroup = consumerGroup;
        this.processedEventRepository = processedEventRepository;
        this.dlqForwarder = dlqForwarder;
        this.sleeper = sleeper;
    }

    /**
     * Entry point invoked by the Spring {@code @KafkaListener}. Handles deduplication, retry,
     * and dead-letter forwarding; delegates the actual work to {@link #handle(ConsumerRecord)}.
     */
    public final void consume(ConsumerRecord<String, String> record) {
        UUID eventId = extractEventId(record);
        if (eventId == null) {
            log.error("Record on topic {} partition {} offset {} has no {} header; dead-lettering",
                    record.topic(), record.partition(), record.offset(), KafkaProducerTemplate.HEADER_EVENT_ID);
            dlqForwarder.forward(record.topic(), record.key(), null, record.value(),
                    "missing eventId header");
            return;
        }

        if (processedEventRepository.existsByConsumerGroupAndEventId(consumerGroup, eventId)) {
            log.debug("Skipping duplicate event {} for consumer group {}", eventId, consumerGroup);
            return;
        }

        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                handle(record);
                recordProcessed(eventId);
                log.debug("Processed event {} for consumer group {} on attempt {}",
                        eventId, consumerGroup, attempt);
                return;
            } catch (RuntimeException ex) {
                lastFailure = ex;
                log.warn("Attempt {}/{} failed processing event {} for consumer group {}: {}",
                        attempt, MAX_RETRIES, eventId, consumerGroup, ex.toString());
                if (attempt < MAX_RETRIES) {
                    pause();
                }
            }
        }

        String reason = lastFailure == null ? "unknown" : lastFailure.toString();
        dlqForwarder.forward(record.topic(), record.key(), eventId.toString(), record.value(), reason);
        log.error("Event {} for consumer group {} dead-lettered after {} attempts",
                eventId, consumerGroup, MAX_RETRIES);
    }

    /**
     * Business processing for a single event. Implementations should throw a
     * {@link RuntimeException} to signal a recoverable failure that should be retried.
     */
    protected abstract void handle(ConsumerRecord<String, String> record);

    private void recordProcessed(UUID eventId) {
        try {
            processedEventRepository.save(new ProcessedEventEntity(consumerGroup, eventId));
        } catch (DataIntegrityViolationException raceLost) {
            // Another instance persisted the same (group, eventId) concurrently — that's fine,
            // the dedup guarantee still holds.
            log.debug("Event {} already recorded for consumer group {} by a concurrent instance",
                    eventId, consumerGroup);
        }
    }

    private void pause() {
        try {
            sleeper.sleep(RETRY_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while awaiting consumer retry", e);
        }
    }

    private static UUID extractEventId(ConsumerRecord<String, String> record) {
        Header header = record.headers().lastHeader(KafkaProducerTemplate.HEADER_EVENT_ID);
        if (header == null || header.value() == null) {
            return null;
        }
        try {
            return UUID.fromString(new String(header.value(), StandardCharsets.UTF_8));
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    /** Seam so tests can avoid real sleeps. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }
}
