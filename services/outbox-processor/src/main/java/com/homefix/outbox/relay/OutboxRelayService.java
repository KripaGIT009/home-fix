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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/**
 * Relays a single PENDING outbox row to Kafka and records the outcome (Requirement 22.4).
 *
 * <p>Publish contract:
 * <ul>
 *   <li>Publishes via the shared {@link KafkaProducerTemplate}, which blocks until the broker
 *       ACKs under {@code acks=all}. Because the producer runs with
 *       {@code enable.idempotence=true}, a payload the broker already holds (e.g. a duplicate
 *       from a prior partial run) is deduplicated broker-side and still returns a successful
 *       ACK — so the row is still marked PUBLISHED, giving idempotent publish.</li>
 *   <li>On ACK the row is marked {@link com.homefix.shared.outbox.OutboxEventStatus#PUBLISHED}
 *       and saved within the same DB transaction.</li>
 *   <li>On failure the attempt is recorded and the relay waits per the
 *       {@link ExponentialBackoff} schedule (1 s, 2 s, 4 s, … capped at 60 s) before the next
 *       attempt, up to the configured maximum.</li>
 *   <li>When the attempt budget is exhausted the row is marked
 *       {@link com.homefix.shared.outbox.OutboxEventStatus#FAILED} and an alert carrying the
 *       event ID, topic, and total attempt count is emitted.</li>
 * </ul>
 *
 * <p>The event payload is never logged, honouring the no-PII-in-logs constraint
 * (Requirement 26.4); only non-PII identifiers (event ID, type, topic, counts) appear in logs.
 */
public class OutboxRelayService {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelayService.class);

    private final OutboxEventRepository repository;
    private final KafkaProducerTemplate producer;
    private final EventTopicResolver topicResolver;
    private final OutboxAlertPort alertPort;
    private final ExponentialBackoff backoff;
    private final int maxAttempts;
    private final Clock clock;
    private final Sleeper sleeper;

    public OutboxRelayService(OutboxEventRepository repository,
                              KafkaProducerTemplate producer,
                              EventTopicResolver topicResolver,
                              OutboxAlertPort alertPort,
                              OutboxProcessorProperties properties,
                              Clock clock,
                              Sleeper sleeper) {
        this.repository = repository;
        this.producer = producer;
        this.topicResolver = topicResolver;
        this.alertPort = alertPort;
        this.backoff = new ExponentialBackoff(
                properties.getRetry().getInitialInterval(),
                properties.getRetry().getMaxInterval());
        this.maxAttempts = properties.getRetry().getMaxAttempts();
        this.clock = clock;
        this.sleeper = sleeper;
    }

    /**
     * Attempts to publish the given event, retrying with exponential backoff on failure.
     *
     * @return {@code true} if the event was published (row marked PUBLISHED); {@code false} if the
     *         attempt budget was exhausted (row marked FAILED and an alert emitted)
     */
    public boolean relay(OutboxEventEntity event) {
        String topic = topicResolver.resolve(event.getEventType());
        String key = event.getAggregateId().toString();
        UUID eventId = event.getId();

        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                producer.send(topic, key, eventId, event.getPayload());
                markPublished(event);
                log.debug("Relayed outbox event {} type={} to topic {} on attempt {}",
                        eventId, event.getEventType(), topic, attempt);
                return true;
            } catch (RuntimeException ex) {
                lastFailure = ex;
                recordFailedAttempt(event, ex);
                log.warn("Publish attempt {}/{} failed for outbox event {} type={} topic={}: {}",
                        attempt, maxAttempts, eventId, event.getEventType(), topic, ex.getMessage());
                if (attempt < maxAttempts && !waitBeforeRetry(attempt)) {
                    // Interrupted while backing off — stop this pass; the row stays PENDING and is
                    // retried on a later poll cycle.
                    return false;
                }
            }
        }

        markFailed(event, lastFailure);
        alertPort.alertPublishExhausted(eventId, topic, maxAttempts);
        log.error("Outbox event {} type={} topic={} marked FAILED after {} attempts",
                eventId, event.getEventType(), topic, maxAttempts);
        return false;
    }

    /**
     * Sleeps for the backoff delay of the just-completed attempt.
     *
     * @return {@code true} if the wait completed, {@code false} if interrupted
     */
    private boolean waitBeforeRetry(int completedAttempt) {
        Duration delay = backoff.delayForAttempt(completedAttempt);
        try {
            sleeper.sleep(delay.toMillis());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Transactional
    protected void markPublished(OutboxEventEntity event) {
        event.markPublished(Instant.now(clock));
        repository.save(event);
    }

    @Transactional
    protected void recordFailedAttempt(OutboxEventEntity event, RuntimeException ex) {
        event.markFailedAttempt(rootMessage(ex));
        repository.save(event);
    }

    @Transactional
    protected void markFailed(OutboxEventEntity event, RuntimeException ex) {
        event.markFailed(rootMessage(ex));
        repository.save(event);
    }

    private static String rootMessage(Throwable ex) {
        if (ex == null) {
            return "unknown publish failure";
        }
        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
        String message = cause.getMessage();
        return message != null ? message : cause.getClass().getSimpleName();
    }
}
