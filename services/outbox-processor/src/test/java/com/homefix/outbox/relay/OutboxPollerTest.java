package com.homefix.outbox.relay;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.homefix.outbox.config.OutboxProcessorProperties;
import com.homefix.outbox.support.InMemoryOutboxEventRepository;
import com.homefix.outbox.support.TestSupport.MutableClock;
import com.homefix.outbox.support.TestSupport.RecordingAlertPort;
import com.homefix.outbox.support.TestSupport.SendCounter;
import com.homefix.shared.outbox.OutboxEventEntity;
import com.homefix.shared.outbox.OutboxEventStatus;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

import static com.homefix.outbox.support.TestSupport.producerAlwaysFailing;
import static com.homefix.outbox.support.TestSupport.producerAlwaysSucceeding;
import static com.homefix.outbox.support.TestSupport.producerFailingThenSucceeding;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link OutboxPoller} with {@link OutboxClaimer} (Requirement 22.4): it claims a
 * batch of due rows and relays each, honours the batch size, leaves rows alone until their
 * persisted next attempt is due, stops at the first publish failure and gives the rest of the batch
 * back unattempted, does the same when the lease has run too short to publish, and walks a
 * permanently failing row through the backoff schedule to FAILED.
 */
class OutboxPollerTest {

    private static final Instant NOW = Instant.parse("2024-07-15T10:00:00Z");

    private final MutableClock clock = new MutableClock(NOW);
    private final InMemoryOutboxEventRepository repository = new InMemoryOutboxEventRepository();
    private final RecordingAlertPort alertPort = new RecordingAlertPort();

    @Test
    void relaysAllDueRowsAndMarksThemPublished() {
        SendCounter producer = producerAlwaysSucceeding();
        for (int i = 0; i < 3; i++) {
            repository.add(pendingEvent(i));
        }
        OutboxPoller poller = pollerWith(producer, 100, 3);

        int published = poller.pollOnce();

        assertThat(published).isEqualTo(3);
        assertThat(repository.countByStatus(OutboxEventStatus.PUBLISHED)).isEqualTo(3);
        assertThat(repository.countByStatus(OutboxEventStatus.PENDING)).isZero();
    }

    @Test
    void limitsWorkToTheConfiguredBatchSize() {
        SendCounter producer = producerAlwaysSucceeding();
        for (int i = 0; i < 5; i++) {
            repository.add(pendingEvent(i));
        }
        OutboxPoller poller = pollerWith(producer, 2, 3);

        int published = poller.pollOnce();

        assertThat(published).isEqualTo(2);
        assertThat(repository.countByStatus(OutboxEventStatus.PENDING)).isEqualTo(3);
    }

    @Test
    void returnsZeroWhenNoPendingRows() {
        OutboxPoller poller = pollerWith(producerAlwaysSucceeding(), 100, 3);
        assertThat(poller.pollOnce()).isZero();
    }

    @Test
    void firstFailureEndsTheCycleAndReleasesTheRestUnattempted() {
        SendCounter producer = producerFailingThenSucceeding(1);
        List<OutboxEventEntity> rows = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            rows.add(pendingEvent(i));
            repository.add(rows.get(i));
        }
        OutboxPoller poller = pollerWith(producer, 100, 3);

        assertThat(poller.pollOnce()).isZero();

        // Only the head row was attempted; it carries the attempt and its backoff.
        assertThat(producer.sends).isEqualTo(1);
        assertThat(rows.get(0).getRetryCount()).isEqualTo(1);
        assertThat(rows.get(0).getNextAttemptAt()).isEqualTo(NOW.plusSeconds(1));
        // The others were given back due immediately, with no attempt charged against them.
        for (OutboxEventEntity released : rows.subList(1, 3)) {
            assertThat(released.getRetryCount()).isZero();
            assertThat(released.getNextAttemptAt()).isEqualTo(NOW);
            assertThat(released.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
        }

        // Next cycle, still inside the head row's backoff: the released rows go out, the failed
        // row is not due and is not touched.
        clock.advance(Duration.ofMillis(500));
        assertThat(poller.pollOnce()).isEqualTo(2);
        assertThat(rows.get(0).getStatus()).isEqualTo(OutboxEventStatus.PENDING);
        assertThat(producer.sends).isEqualTo(3);

        // Once its backoff has elapsed it is retried and published.
        clock.advance(Duration.ofSeconds(1));
        assertThat(poller.pollOnce()).isEqualTo(1);
        assertThat(rows.get(0).getStatus()).isEqualTo(OutboxEventStatus.PUBLISHED);
    }

    @Test
    void rowSkippedForWantOfLeaseEndsTheCycleAndReleasesItWithTheRest() {
        // Every publish takes a minute of a two-minute lease (the clock moves as the ACK arrives),
        // so by the second row too little lease is left to start another one safely.
        List<OutboxEventEntity> rows = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            rows.add(pendingEvent(i));
            repository.add(rows.get(i));
        }
        SendCounter producer = producerAlwaysSucceeding();
        OutboxRelayService slowRelay = new OutboxRelayService(repository, producer.producer(),
                new EventTopicResolver(properties(100, 3).getTopics()), alertPort, properties(100, 3), clock) {
            @Override
            public RelayOutcome relay(OutboxEventEntity event) {
                RelayOutcome outcome = super.relay(event);
                clock.advance(Duration.ofMinutes(1));
                return outcome;
            }
        };
        OutboxPoller poller = new OutboxPoller(claimer(100), slowRelay);

        assertThat(poller.pollOnce()).isEqualTo(1);

        // Only the head row was sent; the skipped row and everything after it were handed back
        // due now rather than left claimed until the lease ran out.
        assertThat(producer.sends).isEqualTo(1);
        assertThat(rows.get(0).getStatus()).isEqualTo(OutboxEventStatus.PUBLISHED);
        for (OutboxEventEntity released : rows.subList(1, 4)) {
            assertThat(released.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
            assertThat(released.getRetryCount()).isZero();
            assertThat(released.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(120));
        }

        // The next cycle claims them straight away with a fresh lease.
        assertThat(poller.pollOnce()).isEqualTo(1);
        assertThat(rows.get(1).getStatus()).isEqualTo(OutboxEventStatus.PUBLISHED);
    }

    @Test
    void claimedRowsAreInvisibleToTheNextClaimUntilTheLeaseExpires() {
        repository.add(pendingEvent(0));
        OutboxClaimer claimer = claimer(100);

        assertThat(claimer.claimBatch()).hasSize(1);
        assertThat(claimer.claimBatch()).isEmpty();

        clock.advance(Duration.ofMinutes(2));
        assertThat(claimer.claimBatch()).hasSize(1);
    }

    @Test
    void permanentlyFailingRowWalksTheBackoffToFailedWithoutBlockingTheLoop() {
        SendCounter producer = producerAlwaysFailing();
        OutboxEventEntity row = pendingEvent(0);
        repository.add(row);
        OutboxPoller poller = pollerWith(producer, 100, 3);

        poller.pollOnce();
        assertThat(row.getRetryCount()).isEqualTo(1);
        // Polling again before the backoff elapses makes no attempt at all.
        assertThat(poller.pollOnce()).isZero();
        assertThat(producer.sends).isEqualTo(1);

        clock.advance(Duration.ofSeconds(1));
        poller.pollOnce();
        clock.advance(Duration.ofSeconds(2));
        poller.pollOnce();

        assertThat(producer.sends).isEqualTo(3);
        assertThat(row.getStatus()).isEqualTo(OutboxEventStatus.FAILED);
        assertThat(row.getLastError()).isEqualTo("simulated broker unavailable");
        assertThat(alertPort.alerts).isEqualTo(1);
        assertThat(alertPort.lastTotalAttempts).isEqualTo(3);

        // FAILED rows are never claimed again.
        clock.advance(Duration.ofHours(1));
        assertThat(poller.pollOnce()).isZero();
        assertThat(producer.sends).isEqualTo(3);
    }

    private OutboxPoller pollerWith(SendCounter producer, int batchSize, int maxAttempts) {
        OutboxProcessorProperties properties = properties(batchSize, maxAttempts);
        EventTopicResolver resolver = new EventTopicResolver(properties.getTopics());
        OutboxRelayService relay = new OutboxRelayService(repository, producer.producer(), resolver,
                alertPort, properties, clock);
        return new OutboxPoller(claimer(batchSize), relay);
    }

    private static OutboxProcessorProperties properties(int batchSize, int maxAttempts) {
        OutboxProcessorProperties properties = new OutboxProcessorProperties();
        properties.setBatchSize(batchSize);
        properties.getRetry().setMaxAttempts(maxAttempts);
        properties.getTopics().setDefaultTopic("domain-events");
        return properties;
    }

    private OutboxClaimer claimer(int batchSize) {
        return new OutboxClaimer(repository, TransactionOperations.withoutTransaction(), clock,
                batchSize, Duration.ofMinutes(2));
    }

    /** Rows created a millisecond apart so claim order (oldest first) is deterministic. */
    private static OutboxEventEntity pendingEvent(int sequence) {
        OutboxEventEntity event = new OutboxEventEntity(UUID.randomUUID(), "Booking", UUID.randomUUID(),
                "BookingCreated", "{\"redacted\":true}");
        org.springframework.test.util.ReflectionTestUtils.setField(event, "createdAt",
                NOW.minusSeconds(60).plusMillis(sequence));
        return event;
    }
}
