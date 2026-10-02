package com.homefix.shared.outbox.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.ByteBuffer;
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
 *   <li>Otherwise, in <em>one</em> transaction, insert the {@code processed_event} row for
 *       {@code (consumerGroup, eventId)} and then invoke {@link #handle(ConsumerRecord)}. If that
 *       insert hits the primary key, another delivery of the same event got there first and the
 *       record is skipped.</li>
 *   <li>On failure, retry up to {@value #MAX_RETRIES} attempts in total with a
 *       {@value #RETRY_DELAY_MS} ms delay between them. If every attempt fails, forward the record
 *       to the dead-letter topic ({@code <topic>.DLT}) and continue — Requirement 22.6.</li>
 * </ol>
 *
 * <p><b>Where the retry delay happens.</b> This class never sleeps. Under a listener container
 * configured by {@link HomefixKafkaConsumerAutoConfiguration} the container stamps each delivery
 * with its attempt number ({@link KafkaHeaders#DELIVERY_ATTEMPT}); a failed attempt before the
 * last is rethrown, and the container's {@code DefaultErrorHandler} re-seeks the record and
 * <em>pauses</em> the listener for the retry delay instead of blocking its thread. The final
 * attempt is dead-lettered here, so the forwarding, its headers and its reason string are the same
 * whichever way the record arrived. Called directly with no attempt header (unit tests, or a
 * container without that wiring) the attempts run back to back, still {@value #MAX_RETRIES} of
 * them and still dead-lettered on exhaustion, but without the delay.
 *
 * <p><b>Why the dedup row is written first, in the handler's transaction.</b> Checking, handling
 * and recording in three separate transactions left two holes. A {@code handle()} that committed
 * its side effects followed by a failed {@code processed_event} insert (a dropped connection) made
 * the redelivery run {@code handle()} again: a second invoice, a second notification. And two
 * deliveries of the same event running concurrently, as happens across a rebalance, both passed
 * the existence check. Now the insert and the handler's database work commit or roll back
 * together, and the insert comes first so the primary key serialises duplicates: the second
 * delivery's insert waits on the first one's uncommitted row and then fails, without its
 * {@code handle()} ever running. The existence check before the transaction stays as a cheap way
 * to drop the common sequential redelivery; it is not what the guarantee rests on.
 *
 * <p>Consequences for subclasses: {@code handle()} runs inside a transaction (the
 * {@code @Transactional} services it calls join it, {@code REQUIRES_NEW} work still commits on
 * its own, and {@code MANDATORY} outbox writes find a transaction to join), and a database
 * connection is held for the whole of {@code handle()}. Side effects outside the database, an SMS
 * or an HTTP call, are not rolled back with it, so work whose own idempotency record must survive
 * a later failure in the same {@code handle()} has to commit that record in its own transaction.
 *
 * <p>The transaction manager is injected through {@link #setTransactionManager}, so the
 * constructor subclasses call is unchanged. Without one (a consumer constructed by hand in a unit
 * test) the check, {@code handle()} and the insert run one after another as before, without the
 * atomicity.
 *
 * <p>Subclasses implement only {@link #handle(ConsumerRecord)} with their business logic and
 * supply their consumer-group name via the constructor.
 */
public abstract class IdempotentKafkaConsumer {

    /** Number of processing attempts after which the event is dead-lettered (Requirement 22.6). */
    public static final int MAX_RETRIES = 3;
    /**
     * Default delay between retry attempts, in milliseconds (Requirement 22.6). Applied by the
     * listener container's error handler, see {@link HomefixKafkaConsumerAutoConfiguration}.
     */
    public static final long RETRY_DELAY_MS = 5_000L;

    private static final Logger log = LoggerFactory.getLogger(IdempotentKafkaConsumer.class);

    private final String consumerGroup;
    private final ProcessedEventRepository processedEventRepository;
    private final DlqForwarder dlqForwarder;
    /** {@code null} until a transaction manager is injected; see {@link #setTransactionManager}. */
    private TransactionTemplate transactionTemplate;

    protected IdempotentKafkaConsumer(String consumerGroup,
                                      ProcessedEventRepository processedEventRepository,
                                      DlqForwarder dlqForwarder) {
        this.consumerGroup = consumerGroup;
        this.processedEventRepository = processedEventRepository;
        this.dlqForwarder = dlqForwarder;
    }

    /**
     * Supplies the transaction manager in which the {@code processed_event} insert and
     * {@link #handle(ConsumerRecord)} run together. Spring calls this on every consumer bean; a
     * service with more than one transaction manager must mark the one owning the
     * {@code processed_event} table {@code @Primary}, or startup fails rather than silently losing
     * the atomicity.
     */
    @Autowired(required = false)
    public final void setTransactionManager(PlatformTransactionManager transactionManager) {
        this.transactionTemplate = transactionManager == null ? null : new TransactionTemplate(transactionManager);
    }

    /**
     * Entry point invoked by the Spring {@code @KafkaListener}. Handles deduplication, retry,
     * and dead-letter forwarding; delegates the actual work to {@link #handle(ConsumerRecord)}.
     *
     * @throws RuntimeException the processing failure of a non-final container-managed attempt,
     *         so the container redelivers the record after the retry delay, or the failure to
     *         dead-letter it, so the record is not acknowledged before it reaches the DLT
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

        Integer deliveryAttempt = extractDeliveryAttempt(record);
        if (deliveryAttempt != null) {
            consumeContainerManagedAttempt(record, eventId, deliveryAttempt);
            return;
        }

        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            lastFailure = tryHandle(record, eventId, attempt);
            if (lastFailure == null) {
                return;
            }
        }
        deadLetter(record, eventId, lastFailure);
    }

    /**
     * One container-managed attempt: success records the event; a failure before the last attempt
     * is rethrown for the container to redeliver after its back-off; the last failure dead-letters.
     */
    private void consumeContainerManagedAttempt(ConsumerRecord<String, String> record, UUID eventId,
                                                int attempt) {
        RuntimeException failure = tryHandle(record, eventId, attempt);
        if (failure == null) {
            return;
        }
        if (attempt < MAX_RETRIES) {
            throw failure;
        }
        deadLetter(record, eventId, failure);
    }

    /**
     * Runs one processing attempt.
     *
     * @return {@code null} on success (the event is then recorded as processed) or when the event
     *         turned out to be a duplicate, otherwise the failure
     */
    private RuntimeException tryHandle(ConsumerRecord<String, String> record, UUID eventId, int attempt) {
        try {
            if (transactionTemplate == null) {
                handle(record);
                recordProcessed(eventId);
            } else {
                transactionTemplate.executeWithoutResult(status -> {
                    claim(eventId);
                    handle(record);
                });
            }
            log.debug("Processed event {} for consumer group {} on attempt {}",
                    eventId, consumerGroup, attempt);
            return null;
        } catch (AlreadyClaimedException duplicate) {
            // The transaction has rolled back; nothing of this delivery was applied.
            log.debug("Skipping event {} for consumer group {}: a concurrent delivery already processed it",
                    eventId, consumerGroup);
            return null;
        } catch (RuntimeException ex) {
            log.warn("Attempt {}/{} failed processing event {} for consumer group {}: {}",
                    attempt, MAX_RETRIES, eventId, consumerGroup, ex.toString());
            return ex;
        }
    }

    private void deadLetter(ConsumerRecord<String, String> record, UUID eventId, RuntimeException lastFailure) {
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

    /**
     * Inserts the dedup row inside the current transaction, flushing so a duplicate surfaces here
     * rather than at commit. A primary-key violation is rethrown as {@link AlreadyClaimedException}
     * so it cannot be mistaken for a {@link DataIntegrityViolationException} raised by
     * {@code handle()} itself, which is an ordinary, retried failure.
     */
    private void claim(UUID eventId) {
        try {
            processedEventRepository.saveAndFlush(new ProcessedEventEntity(consumerGroup, eventId));
        } catch (DataIntegrityViolationException duplicate) {
            throw new AlreadyClaimedException(duplicate);
        }
    }

    /** Non-transactional path, used only without a transaction manager: see the class Javadoc. */
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

    /** Carries a duplicate dedup insert out of the transaction callback, rolling it back. */
    private static final class AlreadyClaimedException extends RuntimeException {
        AlreadyClaimedException(DataIntegrityViolationException cause) {
            super(cause.getMessage(), cause, false, false);
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

    /**
     * The 1-based delivery attempt the listener container stamped on the record (a 4-byte
     * big-endian int, see {@code ContainerProperties#setDeliveryAttemptHeader}), or {@code null}
     * when the record carries none.
     */
    static Integer extractDeliveryAttempt(ConsumerRecord<String, String> record) {
        Header header = record.headers().lastHeader(KafkaHeaders.DELIVERY_ATTEMPT);
        if (header == null || header.value() == null || header.value().length != Integer.BYTES) {
            return null;
        }
        return ByteBuffer.wrap(header.value()).getInt();
    }
}
