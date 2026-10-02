package com.homefix.outbox.relay;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.homefix.outbox.alert.OutboxAlertPort;
import com.homefix.outbox.backoff.ExponentialBackoff;
import com.homefix.outbox.config.OutboxProcessorProperties;
import com.homefix.shared.outbox.OutboxEventEntity;
import com.homefix.shared.outbox.OutboxEventRepository;
import com.homefix.shared.outbox.kafka.KafkaProducerTemplate;
import org.apache.kafka.common.errors.RetriableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;

/**
 * Makes one publish attempt for a claimed outbox row and records the outcome on the row
 * (Requirement 22.4).
 *
 * <p>Publish contract:
 * <ul>
 *   <li>Publishes via the shared {@link KafkaProducerTemplate}, which waits up to
 *       {@code publish-timeout} for the broker ACK under {@code acks=all}. The producer runs with
 *       {@code enable.idempotence=true}, so the producer's own internal retries never duplicate a
 *       write.</li>
 *   <li>On ACK the row is marked {@link com.homefix.shared.outbox.OutboxEventStatus#PUBLISHED}.</li>
 *   <li>On failure the attempt is counted in {@code retry_count}, the error is written to
 *       {@code last_error}, and the next attempt is <em>scheduled</em> by setting
 *       {@code next_attempt_at} per the {@link ExponentialBackoff} schedule (1 s, 2 s, 4 s, …
 *       capped at 60 s). Nothing sleeps: the poll loop moves straight on, and the row is simply not
 *       due again until then.</li>
 *   <li>When the failed attempt was the last of the budget the row is marked
 *       {@link com.homefix.shared.outbox.OutboxEventStatus#FAILED} (with {@code last_error}) and an
 *       alert carrying the event ID, topic, and total attempt count is emitted.</li>
 *   <li>A <em>retriable</em> failure (see {@link #isRetriable}) is the broker's problem, not the
 *       event's, and spends none of that budget: {@code last_error} is written and the row is
 *       rescheduled at the backoff cap ({@code retry.max-interval}), however long the outage lasts.
 *       Counting them, the oldest rows went FAILED after roughly eight minutes of broker downtime
 *       (ten attempts on the 1 s … 60 s curve) and needed an operator to re-queue them. Only a
 *       failure that will recur on every attempt, such as a record too large for the broker or one
 *       that cannot be serialised, consumes attempts and ends in FAILED.</li>
 * </ul>
 *
 * <p><b>Delivery guarantee: at-least-once.</b> Claiming ({@link OutboxClaimer}) ensures two relay
 * instances never work the same row concurrently, but the publish and the PUBLISHED write are two
 * separate systems, so an event can still reach Kafka twice: the relay dies (or its DB write fails)
 * after the broker ACK, a publish times out here yet is delivered by the producer afterwards, or a
 * relay outlives its lease. Consumers deduplicate on the stable {@code eventId} header
 * ({@code IdempotentKafkaConsumer}); an event is never lost, because a row only leaves PENDING once
 * the broker has acknowledged it or the retry budget is spent.
 *
 * <p>The event payload is never logged, honouring the no-PII-in-logs constraint
 * (Requirement 26.4); only non-PII identifiers (event ID, type, topic, counts) appear in logs.
 */
public class OutboxRelayService {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelayService.class);

    /** What a single {@link #relay} call did with the row. */
    public enum RelayOutcome {
        /** Broker ACKed; row marked PUBLISHED. */
        PUBLISHED,
        /** Publish failed; attempt recorded and the next one scheduled. */
        RETRY_SCHEDULED,
        /** Publish failed and the attempt budget is spent; row marked FAILED, ops alerted. */
        FAILED,
        /** Not attempted: too little of the claim lease was left to finish a publish within it. */
        SKIPPED,
        /**
         * The outcome could not be written because another relay re-claimed the row after this
         * relay's lease expired; the new owner's state stands.
         */
        CLAIM_LOST;

        /** Whether the publish itself was attempted and failed. */
        public boolean isPublishFailure() {
            return this == RETRY_SCHEDULED || this == FAILED;
        }
    }

    private final OutboxEventRepository repository;
    private final KafkaProducerTemplate producer;
    private final EventTopicResolver topicResolver;
    private final OutboxAlertPort alertPort;
    private final ExponentialBackoff backoff;
    private final int maxAttempts;
    private final Duration publishTimeout;
    /**
     * Longest a publish can take end to end: the producer's {@code max.block.ms}, spent inside
     * {@code send()} before the record is handed over (waiting for metadata while the broker is
     * unreachable), plus {@code publish-timeout} waiting for the ACK after it.
     */
    private final Duration publishBudget;
    private final Duration maxRetryInterval;
    private final Clock clock;

    public OutboxRelayService(OutboxEventRepository repository,
                              KafkaProducerTemplate producer,
                              EventTopicResolver topicResolver,
                              OutboxAlertPort alertPort,
                              OutboxProcessorProperties properties,
                              Clock clock) {
        if (properties.getRetry().getMaxAttempts() < 1) {
            throw new IllegalArgumentException(
                    "retry.max-attempts must be >= 1, got " + properties.getRetry().getMaxAttempts());
        }
        Duration publishBudget = properties.getPublishTimeout().plus(producer.maxBlockTime());
        if (properties.getClaimLease().compareTo(publishBudget) <= 0) {
            throw new IllegalArgumentException("claim-lease (" + properties.getClaimLease()
                    + ") must exceed publish-timeout (" + properties.getPublishTimeout()
                    + ") plus the producer's max.block.ms (" + producer.maxBlockTime()
                    + "), or no claimed row could ever be published within its lease");
        }
        this.repository = repository;
        this.producer = producer;
        this.topicResolver = topicResolver;
        this.alertPort = alertPort;
        this.backoff = new ExponentialBackoff(
                properties.getRetry().getInitialInterval(),
                properties.getRetry().getMaxInterval());
        this.maxAttempts = properties.getRetry().getMaxAttempts();
        this.publishTimeout = properties.getPublishTimeout();
        this.publishBudget = publishBudget;
        this.maxRetryInterval = properties.getRetry().getMaxInterval();
        this.clock = clock;
    }

    /**
     * Makes one publish attempt for a row claimed by {@link OutboxClaimer} and persists the result.
     * Never sleeps and never retries in-line.
     */
    public RelayOutcome relay(OutboxEventEntity event) {
        String topic = topicResolver.resolve(event.getEventType());
        UUID eventId = event.getId();

        Instant lease = event.getNextAttemptAt();
        if (lease != null && !clock.instant().plus(publishBudget).isBefore(lease)) {
            // Starting now could finish after the lease, by which time another relay may have
            // claimed the row and published it too. The caller hands it back (OutboxPoller).
            log.debug("Skipping outbox event {}: claim lease ends {}, before a publish started now could"
                    + " time out ({})", eventId, lease, publishBudget);
            return RelayOutcome.SKIPPED;
        }

        try {
            producer.send(topic, event.getAggregateId().toString(), eventId, event.getPayload(), publishTimeout);
        } catch (RuntimeException ex) {
            return recordFailedAttempt(event, topic, ex);
        }

        event.markPublished(clock.instant());
        if (!saveOutcome(event)) {
            return RelayOutcome.CLAIM_LOST;
        }
        log.debug("Relayed outbox event {} type={} to topic {} on attempt {}",
                eventId, event.getEventType(), topic, event.getRetryCount() + 1);
        return RelayOutcome.PUBLISHED;
    }

    private RelayOutcome recordFailedAttempt(OutboxEventEntity event, String topic, RuntimeException ex) {
        String error = rootMessage(ex);
        if (isRetriable(ex)) {
            return recordTransientFailure(event, topic, error);
        }
        event.markFailedAttempt(error);
        int attempts = event.getRetryCount();

        if (attempts >= maxAttempts) {
            event.markFailed(error);
            if (!saveOutcome(event)) {
                return RelayOutcome.CLAIM_LOST;
            }
            alertPort.alertPublishExhausted(event.getId(), topic, attempts);
            log.error("Outbox event {} type={} topic={} marked FAILED after {} attempts: {}",
                    event.getId(), event.getEventType(), topic, attempts, error);
            return RelayOutcome.FAILED;
        }

        Instant nextAttemptAt = clock.instant().plus(backoff.delayForAttempt(attempts));
        event.scheduleNextAttempt(nextAttemptAt);
        if (!saveOutcome(event)) {
            return RelayOutcome.CLAIM_LOST;
        }
        log.warn("Publish attempt {}/{} failed for outbox event {} type={} topic={}; next attempt at {}: {}",
                attempts, maxAttempts, event.getId(), event.getEventType(), topic, nextAttemptAt, error);
        return RelayOutcome.RETRY_SCHEDULED;
    }

    private RelayOutcome recordTransientFailure(OutboxEventEntity event, String topic, String error) {
        event.recordTransientFailure(error);
        Instant nextAttemptAt = clock.instant().plus(maxRetryInterval);
        event.scheduleNextAttempt(nextAttemptAt);
        if (!saveOutcome(event)) {
            return RelayOutcome.CLAIM_LOST;
        }
        log.warn("Publish of outbox event {} type={} topic={} failed with a retriable broker error;"
                        + " not counted against its {} attempts ({} used), next attempt at {}: {}",
                event.getId(), event.getEventType(), topic, maxAttempts, event.getRetryCount(),
                nextAttemptAt, error);
        return RelayOutcome.RETRY_SCHEDULED;
    }

    /**
     * Whether a publish failure says nothing about the event itself, so the same record may well
     * succeed later: anything in the cause chain that Kafka marks {@link RetriableException}
     * (its own {@code TimeoutException} for metadata or delivery, leader changes, not enough
     * replicas, a disconnect), or the relay's own wait for the ACK running out
     * ({@link java.util.concurrent.TimeoutException}), or the wait being interrupted on shutdown.
     * Everything else, {@code RecordTooLargeException}, a {@code SerializationException}, an
     * authorisation failure, is taken to recur on every attempt.
     */
    static boolean isRetriable(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof RetriableException
                    || t instanceof java.util.concurrent.TimeoutException
                    || t instanceof InterruptedException) {
                return true;
            }
        }
        return false;
    }

    /**
     * Persists the outcome in its own short transaction (the repository's).
     *
     * @return {@code false} if the row's version moved on, meaning another relay re-claimed it
     */
    private boolean saveOutcome(OutboxEventEntity event) {
        try {
            repository.save(event);
            return true;
        } catch (OptimisticLockingFailureException claimLost) {
            log.warn("Outbox event {} was re-claimed by another relay after this relay's lease expired;"
                    + " discarding this relay's outcome ({})", event.getId(), event.getStatus());
            return false;
        }
    }

    private static String rootMessage(Throwable ex) {
        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
        String message = cause.getMessage();
        if (message == null) {
            message = ex.getMessage();
        }
        return message != null ? message : cause.getClass().getSimpleName();
    }
}
