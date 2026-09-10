package com.homefix.outbox.relay;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import com.homefix.outbox.config.OutboxProcessorProperties;
import com.homefix.outbox.support.InMemoryOutboxEventRepository;
import com.homefix.outbox.support.TestSupport.RecordingAlertPort;
import com.homefix.outbox.support.TestSupport.RecordingSleeper;
import com.homefix.outbox.support.TestSupport.SendCounter;
import com.homefix.shared.outbox.OutboxEventEntity;
import com.homefix.shared.outbox.OutboxEventStatus;
import com.homefix.shared.outbox.kafka.KafkaProducerTemplate;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.junit.jupiter.api.Test;

import static com.homefix.outbox.support.TestSupport.producerAlwaysFailing;
import static com.homefix.outbox.support.TestSupport.producerAlwaysSucceeding;
import static com.homefix.outbox.support.TestSupport.producerFailingThenSucceeding;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link OutboxRelayService} (Requirement 22.4):
 * <ul>
 *   <li>a successful publish marks the row PUBLISHED;</li>
 *   <li>publish failures retry on the exponential-backoff schedule (1 s, 2 s, 4 s, …);</li>
 *   <li>exhausting the attempt budget marks the row FAILED and alerts ops with the event ID,
 *       topic, and total attempt count;</li>
 *   <li>the shared producer runs with the idempotent config so a broker-side duplicate still
 *       ACKs and the row is still marked PUBLISHED.</li>
 * </ul>
 *
 * <p>Pure in-memory fakes; no Spring context, database, or network.
 */
class OutboxRelayServiceTest {

    private static final Instant NOW = Instant.parse("2024-07-15T10:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final InMemoryOutboxEventRepository repository = new InMemoryOutboxEventRepository();
    private final RecordingAlertPort alertPort = new RecordingAlertPort();
    private final RecordingSleeper sleeper = new RecordingSleeper();

    @Test
    void successfulPublishMarksRowPublishedAndStampsTime() {
        SendCounter producer = producerAlwaysSucceeding();
        OutboxEventEntity event = pendingEvent("BookingCreated");
        repository.add(event);
        OutboxRelayService service = relayWith(producer.producer(), properties(10));

        boolean published = service.relay(event);

        assertThat(published).isTrue();
        OutboxEventEntity stored = repository.findById(event.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(OutboxEventStatus.PUBLISHED);
        assertThat(stored.getPublishedAt()).isEqualTo(NOW);
        assertThat(producer.sends).isEqualTo(1);
        assertThat(producer.lastTopic).isEqualTo("BookingCreated");
        assertThat(alertPort.alerts).isZero();
        // No backoff waited since the first attempt succeeded.
        assertThat(sleeper.sleeps).isEmpty();
    }

    @Test
    void retriesOnFailureThenMarksPublishedOnAck() {
        SendCounter producer = producerFailingThenSucceeding(2); // fail twice, ACK on 3rd
        OutboxEventEntity event = pendingEvent("PaymentCompleted");
        repository.add(event);
        OutboxRelayService service = relayWith(producer.producer(), properties(10));

        boolean published = service.relay(event);

        assertThat(published).isTrue();
        assertThat(producer.sends).isEqualTo(3);
        assertThat(repository.findById(event.getId()).orElseThrow().getStatus())
                .isEqualTo(OutboxEventStatus.PUBLISHED);
        // Waited before attempt 2 and attempt 3: 1 s, then 2 s.
        assertThat(sleeper.sleeps).containsExactly(1000L, 2000L);
        assertThat(alertPort.alerts).isZero();
    }

    @Test
    void backoffScheduleFollowsExponentialCurveUpToTheCap() {
        SendCounter producer = producerAlwaysFailing();
        OutboxEventEntity event = pendingEvent("JobStarted");
        repository.add(event);
        OutboxRelayService service = relayWith(producer.producer(), properties(10));

        service.relay(event);

        // 10 attempts => 9 backoff waits between them: 1,2,4,8,16,32,60,60,60 seconds.
        assertThat(sleeper.sleeps).containsExactly(
                1000L, 2000L, 4000L, 8000L, 16000L, 32000L, 60000L, 60000L, 60000L);
    }

    @Test
    void marksRowFailedAndAlertsAfterMaxRetriesExhausted() {
        SendCounter producer = producerAlwaysFailing();
        OutboxEventEntity event = pendingEvent("BookingCreated");
        repository.add(event);
        OutboxRelayService service = relayWith(producer.producer(), properties(10));

        boolean published = service.relay(event);

        assertThat(published).isFalse();
        assertThat(producer.sends).isEqualTo(10);
        OutboxEventEntity stored = repository.findById(event.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(OutboxEventStatus.FAILED);
        // Alert carries event ID, topic, and total attempt count (Requirement 22.4).
        assertThat(alertPort.alerts).isEqualTo(1);
        assertThat(alertPort.lastEventId).isEqualTo(event.getId());
        assertThat(alertPort.lastTopic).isEqualTo("BookingCreated");
        assertThat(alertPort.lastTotalAttempts).isEqualTo(10);
    }

    @Test
    void honoursConfiguredMaxAttempts() {
        SendCounter producer = producerAlwaysFailing();
        OutboxEventEntity event = pendingEvent("BookingCreated");
        repository.add(event);
        OutboxRelayService service = relayWith(producer.producer(), properties(3));

        service.relay(event);

        assertThat(producer.sends).isEqualTo(3);
        assertThat(alertPort.lastTotalAttempts).isEqualTo(3);
        assertThat(repository.findById(event.getId()).orElseThrow().getStatus())
                .isEqualTo(OutboxEventStatus.FAILED);
    }

    @Test
    void sharedProducerEnforcesIdempotentConfig() {
        // A broker-side duplicate (idempotent producer) still ACKs, so the row is marked PUBLISHED.
        var config = KafkaProducerTemplate.idempotentProducerConfig();
        assertThat(config.get(ProducerConfig.ACKS_CONFIG)).isEqualTo("all");
        assertThat(config.get(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG)).isEqualTo(true);

        SendCounter producer = producerAlwaysSucceeding();
        OutboxEventEntity event = pendingEvent("PaymentCompleted");
        repository.add(event);
        OutboxRelayService service = relayWith(producer.producer(), properties(10));

        assertThat(service.relay(event)).isTrue();
        assertThat(repository.findById(event.getId()).orElseThrow().getStatus())
                .isEqualTo(OutboxEventStatus.PUBLISHED);
    }

    private OutboxRelayService relayWith(KafkaProducerTemplate producer,
                                         OutboxProcessorProperties properties) {
        EventTopicResolver resolver = new EventTopicResolver(properties.getTopics());
        return new OutboxRelayService(repository, producer, resolver, alertPort, properties,
                FIXED_CLOCK, sleeper);
    }

    private static OutboxEventEntity pendingEvent(String eventType) {
        return new OutboxEventEntity(UUID.randomUUID(), "Booking", UUID.randomUUID(),
                eventType, "{\"redacted\":true}");
    }

    private static OutboxProcessorProperties properties(int maxAttempts) {
        OutboxProcessorProperties p = new OutboxProcessorProperties();
        p.getRetry().setInitialInterval(Duration.ofSeconds(1));
        p.getRetry().setMaxInterval(Duration.ofSeconds(60));
        p.getRetry().setMaxAttempts(maxAttempts);
        p.getTopics().setDefaultTopic("domain-events");
        p.getTopics().setMapping(java.util.Map.of(
                "BookingCreated", "BookingCreated",
                "PaymentCompleted", "PaymentCompleted",
                "JobStarted", "JobStarted"));
        return p;
    }
}
