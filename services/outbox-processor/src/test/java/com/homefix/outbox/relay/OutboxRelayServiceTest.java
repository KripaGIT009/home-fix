package com.homefix.outbox.relay;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.homefix.outbox.config.OutboxProcessorProperties;
import com.homefix.outbox.relay.OutboxRelayService.RelayOutcome;
import com.homefix.outbox.support.InMemoryOutboxEventRepository;
import com.homefix.outbox.support.TestSupport.MutableClock;
import com.homefix.outbox.support.TestSupport.RecordingAlertPort;
import com.homefix.outbox.support.TestSupport.SendCounter;
import com.homefix.shared.outbox.OutboxEventEntity;
import com.homefix.shared.outbox.OutboxEventStatus;
import com.homefix.shared.outbox.kafka.KafkaProducerTemplate;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.errors.NotEnoughReplicasException;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.KafkaException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import static com.homefix.outbox.support.TestSupport.producerAlwaysFailing;
import static com.homefix.outbox.support.TestSupport.producerAlwaysFailingWith;
import static com.homefix.outbox.support.TestSupport.producerAlwaysSucceeding;
import static com.homefix.outbox.support.TestSupport.producerFailingThenSucceeding;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link OutboxRelayService} (Requirement 22.4):
 * <ul>
 *   <li>a successful publish marks the row PUBLISHED;</li>
 *   <li>a failed publish is never slept on: the attempt count, {@code last_error} and the next
 *       attempt time are written to the row, following the exponential schedule (1 s, 2 s, 4 s, …
 *       capped at 60 s);</li>
 *   <li>the attempt that exhausts the budget marks the row FAILED, keeps {@code last_error}, and
 *       alerts ops with the event ID, topic, and total attempt count;</li>
 *   <li>a retriable broker failure spends no attempt and is rescheduled at the backoff cap, so an
 *       outage of any length never marks a row FAILED; a non-retriable one does spend attempts;</li>
 *   <li>a publish is not started on too little claim lease (publish timeout plus the producer's
 *       {@code max.block.ms}), and an outcome write that lost the row to another relay is reported
 *       rather than thrown.</li>
 * </ul>
 *
 * <p>Pure in-memory fakes; no Spring context, database, or network.
 */
class OutboxRelayServiceTest {

    private static final Instant NOW = Instant.parse("2024-07-15T10:00:00Z");
    private static final Duration LEASE = Duration.ofMinutes(2);

    private final MutableClock clock = new MutableClock(NOW);
    private final InMemoryOutboxEventRepository repository = new InMemoryOutboxEventRepository();
    private final RecordingAlertPort alertPort = new RecordingAlertPort();

    @Test
    void successfulPublishMarksRowPublishedAndStampsTime() {
        SendCounter producer = producerAlwaysSucceeding();
        OutboxEventEntity event = pendingEvent("BookingCreated");
        OutboxRelayService service = relayWith(producer.producer(), properties(10));

        RelayOutcome outcome = claimAndRelay(service, event);

        assertThat(outcome).isEqualTo(RelayOutcome.PUBLISHED);
        OutboxEventEntity stored = repository.findById(event.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(OutboxEventStatus.PUBLISHED);
        assertThat(stored.getPublishedAt()).isEqualTo(NOW);
        assertThat(stored.getNextAttemptAt()).isNull();
        assertThat(producer.sends).isEqualTo(1);
        assertThat(producer.lastTopic).isEqualTo("BookingCreated");
        assertThat(producer.lastKey).isEqualTo(event.getAggregateId().toString());
        assertThat(alertPort.alerts).isZero();
    }

    @Test
    void failedPublishPersistsAttemptErrorAndNextAttemptInsteadOfSleeping() {
        SendCounter producer = producerAlwaysFailing();
        OutboxEventEntity event = pendingEvent("PaymentCompleted");
        OutboxRelayService service = relayWith(producer.producer(), properties(10));

        long started = System.nanoTime();
        RelayOutcome outcome = claimAndRelay(service, event);
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;

        assertThat(outcome).isEqualTo(RelayOutcome.RETRY_SCHEDULED);
        OutboxEventEntity stored = repository.findById(event.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
        assertThat(stored.getRetryCount()).isEqualTo(1);
        assertThat(stored.getLastError()).isEqualTo("simulated broker unavailable");
        assertThat(stored.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(1));
        // One attempt per call, and the call returns at once: the delay lives on the row.
        assertThat(producer.sends).isEqualTo(1);
        assertThat(elapsedMillis).isLessThan(1_000);
        assertThat(alertPort.alerts).isZero();
    }

    @Test
    void persistedBackoffFollowsTheExponentialCurveUpToTheCap() {
        SendCounter producer = producerAlwaysFailing();
        OutboxEventEntity event = pendingEvent("JobStarted");
        OutboxRelayService service = relayWith(producer.producer(), properties(10));

        List<Long> scheduledDelays = new ArrayList<>();
        for (int attempt = 1; attempt < 10; attempt++) {
            assertThat(claimAndRelay(service, event)).isEqualTo(RelayOutcome.RETRY_SCHEDULED);
            scheduledDelays.add(Duration.between(clock.instant(), event.getNextAttemptAt()).toSeconds());
        }

        // 9 failed attempts before the last: 1,2,4,8,16,32,60,60,60 seconds until the next one.
        assertThat(scheduledDelays).containsExactly(1L, 2L, 4L, 8L, 16L, 32L, 60L, 60L, 60L);
        assertThat(event.getRetryCount()).isEqualTo(9);
        assertThat(alertPort.alerts).isZero();
    }

    @Test
    void retriesOnLaterCyclesThenMarksPublishedOnAck() {
        SendCounter producer = producerFailingThenSucceeding(2); // fail twice, ACK on 3rd
        OutboxEventEntity event = pendingEvent("PaymentCompleted");
        OutboxRelayService service = relayWith(producer.producer(), properties(10));

        assertThat(claimAndRelay(service, event)).isEqualTo(RelayOutcome.RETRY_SCHEDULED);
        assertThat(claimAndRelay(service, event)).isEqualTo(RelayOutcome.RETRY_SCHEDULED);
        assertThat(claimAndRelay(service, event)).isEqualTo(RelayOutcome.PUBLISHED);

        OutboxEventEntity stored = repository.findById(event.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(OutboxEventStatus.PUBLISHED);
        assertThat(stored.getRetryCount()).isEqualTo(2);
        assertThat(stored.getLastError()).isNull();
        assertThat(stored.getNextAttemptAt()).isNull();
        assertThat(producer.sends).isEqualTo(3);
        assertThat(alertPort.alerts).isZero();
    }

    @Test
    void marksRowFailedWithLastErrorAndAlertsOnTheFinalAttempt() {
        SendCounter producer = producerAlwaysFailing();
        OutboxEventEntity event = pendingEvent("BookingCreated");
        OutboxRelayService service = relayWith(producer.producer(), properties(3));

        assertThat(claimAndRelay(service, event)).isEqualTo(RelayOutcome.RETRY_SCHEDULED);
        assertThat(claimAndRelay(service, event)).isEqualTo(RelayOutcome.RETRY_SCHEDULED);
        assertThat(alertPort.alerts).isZero();
        assertThat(claimAndRelay(service, event)).isEqualTo(RelayOutcome.FAILED);

        assertThat(producer.sends).isEqualTo(3);
        OutboxEventEntity stored = repository.findById(event.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(OutboxEventStatus.FAILED);
        assertThat(stored.getRetryCount()).isEqualTo(3);
        assertThat(stored.getLastError()).isEqualTo("simulated broker unavailable");
        assertThat(stored.getNextAttemptAt()).isNull();
        // Alert carries event ID, topic, and total attempt count (Requirement 22.4).
        assertThat(alertPort.alerts).isEqualTo(1);
        assertThat(alertPort.lastEventId).isEqualTo(event.getId());
        assertThat(alertPort.lastTopic).isEqualTo("BookingCreated");
        assertThat(alertPort.lastTotalAttempts).isEqualTo(3);
    }

    @Test
    void doesNotStartAPublishThatCouldOutliveTheClaimLease() {
        SendCounter producer = producerAlwaysSucceeding();
        OutboxEventEntity event = pendingEvent("BookingCreated");
        repository.add(event);
        OutboxRelayService service = relayWith(producer.producer(), properties(10));
        // 10 s of lease left, but a publish may take up to the 30 s publish timeout.
        event.scheduleNextAttempt(NOW.plusSeconds(10));

        assertThat(service.relay(event)).isEqualTo(RelayOutcome.SKIPPED);
        assertThat(producer.sends).isZero();
        assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
        assertThat(event.getRetryCount()).isZero();
    }

    @Test
    void doesNotStartAPublishWhoseMaxBlockTimeCouldOutliveTheClaimLease() {
        // 50 s of lease covers the 30 s publish timeout, but not that plus a 30 s max.block.ms
        // spent inside send() before the ACK wait even starts.
        SendCounter blocking = producerAlwaysSucceeding(Map.of(ProducerConfig.MAX_BLOCK_MS_CONFIG, "30000"));
        OutboxEventEntity event = pendingEvent("BookingCreated");
        repository.add(event);
        event.scheduleNextAttempt(NOW.plusSeconds(50));

        assertThat(relayWith(blocking.producer(), properties(10)).relay(event)).isEqualTo(RelayOutcome.SKIPPED);
        assertThat(blocking.sends).isZero();

        // With a 10 s max.block.ms the same lease is enough.
        SendCounter quick = producerAlwaysSucceeding(Map.of(ProducerConfig.MAX_BLOCK_MS_CONFIG, 10_000));
        assertThat(relayWith(quick.producer(), properties(10)).relay(event)).isEqualTo(RelayOutcome.PUBLISHED);
    }

    @Test
    void retriableBrokerFailureSpendsNoAttemptAndRetriesAtTheBackoffCapIndefinitely() {
        SendCounter producer = producerAlwaysFailingWith(
                new KafkaException("send failed", new TimeoutException("Topic PaymentCompleted not present in metadata")));
        OutboxEventEntity event = pendingEvent("PaymentCompleted");
        OutboxRelayService service = relayWith(producer.producer(), properties(3));

        // Far more failures than the budget of 3: a long outage.
        for (int failure = 1; failure <= 12; failure++) {
            assertThat(claimAndRelay(service, event)).isEqualTo(RelayOutcome.RETRY_SCHEDULED);
            assertThat(Duration.between(clock.instant(), event.getNextAttemptAt())).isEqualTo(Duration.ofSeconds(60));
        }

        OutboxEventEntity stored = repository.findById(event.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
        assertThat(stored.getRetryCount()).isZero();
        assertThat(stored.getLastError()).isEqualTo("send failed");
        assertThat(producer.sends).isEqualTo(12);
        assertThat(alertPort.alerts).isZero();
    }

    @Test
    void retriableFailuresDoNotEatIntoTheBudgetLeftForRealOnes() {
        OutboxEventEntity event = pendingEvent("BookingCreated");
        OutboxRelayService outage = relayWith(
                producerAlwaysFailingWith(new NotEnoughReplicasException("not enough replicas")).producer(),
                properties(2));
        OutboxRelayService tooLarge = relayWith(
                producerAlwaysFailingWith(new RecordTooLargeException("record too large")).producer(),
                properties(2));

        for (int i = 0; i < 5; i++) {
            assertThat(claimAndRelay(outage, event)).isEqualTo(RelayOutcome.RETRY_SCHEDULED);
        }
        assertThat(event.getRetryCount()).isZero();

        // A failure that will recur on every attempt still walks the row to FAILED.
        assertThat(claimAndRelay(tooLarge, event)).isEqualTo(RelayOutcome.RETRY_SCHEDULED);
        assertThat(event.getRetryCount()).isEqualTo(1);
        assertThat(claimAndRelay(tooLarge, event)).isEqualTo(RelayOutcome.FAILED);
        assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.FAILED);
        assertThat(alertPort.lastTotalAttempts).isEqualTo(2);
    }

    @Test
    void classifiesPublishFailuresByTheirCauseChain() {
        // The relay's own ACK wait running out, as KafkaProducerTemplate reports it.
        assertThat(OutboxRelayService.isRetriable(new KafkaProducerTemplate.KafkaPublishException(
                "Timed out", new java.util.concurrent.TimeoutException()))).isTrue();
        // Kafka's retriable family, however deeply Spring wraps it.
        assertThat(OutboxRelayService.isRetriable(new KafkaProducerTemplate.KafkaPublishException(
                "Failed", new KafkaException("send failed", new TimeoutException("expired"))))).isTrue();
        assertThat(OutboxRelayService.isRetriable(new NotEnoughReplicasException("x"))).isTrue();
        assertThat(OutboxRelayService.isRetriable(new KafkaProducerTemplate.KafkaPublishException(
                "Interrupted", new InterruptedException()))).isTrue();
        // Failures that will recur on every attempt.
        assertThat(OutboxRelayService.isRetriable(new KafkaProducerTemplate.KafkaPublishException(
                "Failed", new RecordTooLargeException("too large")))).isFalse();
        assertThat(OutboxRelayService.isRetriable(new SerializationException("bad"))).isFalse();
        assertThat(OutboxRelayService.isRetriable(new IllegalStateException("boom"))).isFalse();
    }

    @Test
    void outcomeWriteRejectedByVersionCheckIsReportedAsClaimLost() {
        InMemoryOutboxEventRepository conflicting = new InMemoryOutboxEventRepository() {
            @Override
            public <S extends OutboxEventEntity> S save(S entity) {
                throw new ObjectOptimisticLockingFailureException(OutboxEventEntity.class, entity.getId());
            }
        };
        OutboxEventEntity event = pendingEvent("BookingCreated");
        conflicting.add(event);

        OutboxRelayService published = new OutboxRelayService(conflicting, producerAlwaysSucceeding().producer(),
                resolver(properties(10)), alertPort, properties(10), clock);
        assertThat(published.relay(event)).isEqualTo(RelayOutcome.CLAIM_LOST);

        OutboxRelayService exhausted = new OutboxRelayService(conflicting, producerAlwaysFailing().producer(),
                resolver(properties(1)), alertPort, properties(1), clock);
        assertThat(exhausted.relay(pendingEvent("BookingCreated"))).isEqualTo(RelayOutcome.CLAIM_LOST);
        // The new owner decides the row's fate; this relay raises no alert for it.
        assertThat(alertPort.alerts).isZero();
    }

    @Test
    void rejectsALeaseThatCannotCoverAPublish() {
        OutboxProcessorProperties p = properties(10);
        p.setClaimLease(Duration.ofSeconds(30));
        p.setPublishTimeout(Duration.ofSeconds(30));

        assertThatThrownBy(() -> relayWith(producerAlwaysSucceeding().producer(), p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("claim-lease");
    }

    @Test
    void rejectsALeaseThatCannotCoverThePublishTimeoutPlusMaxBlock() {
        OutboxProcessorProperties p = properties(10);
        p.setClaimLease(Duration.ofSeconds(40));
        p.setPublishTimeout(Duration.ofSeconds(30));

        // 40 s exceeds the 30 s publish timeout alone, but not with 10 s of max.block.ms on top.
        assertThatThrownBy(() -> relayWith(
                producerAlwaysSucceeding(Map.of(ProducerConfig.MAX_BLOCK_MS_CONFIG, "10000")).producer(), p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max.block.ms");
    }

    @Test
    void sharedProducerEnforcesIdempotentConfig() {
        // The producer runs idempotent, so its own internal retries never duplicate a write.
        var config = KafkaProducerTemplate.idempotentProducerConfig();
        assertThat(config.get(ProducerConfig.ACKS_CONFIG)).isEqualTo("all");
        assertThat(config.get(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG)).isEqualTo(true);

        SendCounter producer = producerAlwaysSucceeding();
        OutboxEventEntity event = pendingEvent("PaymentCompleted");
        OutboxRelayService service = relayWith(producer.producer(), properties(10));

        assertThat(claimAndRelay(service, event)).isEqualTo(RelayOutcome.PUBLISHED);
        assertThat(repository.findById(event.getId()).orElseThrow().getStatus())
                .isEqualTo(OutboxEventStatus.PUBLISHED);
    }

    /**
     * Moves the clock to when the row is next due (as the poller would only see it then), claims
     * it the way {@link OutboxClaimer} does, and relays it.
     */
    private RelayOutcome claimAndRelay(OutboxRelayService service, OutboxEventEntity event) {
        repository.add(event);
        if (event.getNextAttemptAt() != null && event.getNextAttemptAt().isAfter(clock.instant())) {
            clock.set(event.getNextAttemptAt());
        }
        event.scheduleNextAttempt(clock.instant().plus(LEASE));
        return service.relay(event);
    }

    private OutboxRelayService relayWith(KafkaProducerTemplate producer, OutboxProcessorProperties properties) {
        return new OutboxRelayService(repository, producer, resolver(properties), alertPort, properties, clock);
    }

    private static EventTopicResolver resolver(OutboxProcessorProperties properties) {
        return new EventTopicResolver(properties.getTopics());
    }

    private static OutboxEventEntity pendingEvent(String eventType) {
        return new OutboxEventEntity(UUID.randomUUID(), "Booking", UUID.randomUUID(),
                eventType, "{\"redacted\":true}");
    }

    private static OutboxProcessorProperties properties(int maxAttempts) {
        OutboxProcessorProperties p = new OutboxProcessorProperties();
        p.setClaimLease(LEASE);
        p.setPublishTimeout(Duration.ofSeconds(30));
        p.getRetry().setInitialInterval(Duration.ofSeconds(1));
        p.getRetry().setMaxInterval(Duration.ofSeconds(60));
        p.getRetry().setMaxAttempts(maxAttempts);
        p.getTopics().setDefaultTopic("domain-events");
        p.getTopics().setMapping(Map.of(
                "BookingCreated", "BookingCreated",
                "PaymentCompleted", "PaymentCompleted",
                "JobStarted", "JobStarted"));
        return p;
    }
}
