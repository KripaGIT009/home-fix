package com.homefix.outbox.relay;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.UUID;

import com.homefix.outbox.config.OutboxProcessorProperties;
import com.homefix.outbox.support.InMemoryOutboxEventRepository;
import com.homefix.outbox.support.TestSupport.RecordingAlertPort;
import com.homefix.outbox.support.TestSupport.RecordingSleeper;
import com.homefix.outbox.support.TestSupport.SendCounter;
import com.homefix.shared.outbox.OutboxEventEntity;
import com.homefix.shared.outbox.OutboxEventStatus;
import org.junit.jupiter.api.Test;

import static com.homefix.outbox.support.TestSupport.producerAlwaysSucceeding;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link OutboxPoller}: it drains a batch of PENDING rows, relays each, and honours
 * the configured batch size.
 */
class OutboxPollerTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(java.time.Instant.parse("2024-07-15T10:00:00Z"), ZoneOffset.UTC);

    private final InMemoryOutboxEventRepository repository = new InMemoryOutboxEventRepository();
    private final RecordingAlertPort alertPort = new RecordingAlertPort();
    private final RecordingSleeper sleeper = new RecordingSleeper();

    @Test
    void relaysAllPendingRowsAndMarksThemPublished() {
        SendCounter producer = producerAlwaysSucceeding();
        for (int i = 0; i < 3; i++) {
            repository.add(pendingEvent());
        }
        OutboxPoller poller = pollerWith(producer, 100);

        int published = poller.pollOnce();

        assertThat(published).isEqualTo(3);
        assertThat(repository.countByStatus(OutboxEventStatus.PUBLISHED)).isEqualTo(3);
        assertThat(repository.countByStatus(OutboxEventStatus.PENDING)).isZero();
    }

    @Test
    void limitsWorkToTheConfiguredBatchSize() {
        SendCounter producer = producerAlwaysSucceeding();
        for (int i = 0; i < 5; i++) {
            repository.add(pendingEvent());
        }
        OutboxPoller poller = pollerWith(producer, 2);

        int published = poller.pollOnce();

        assertThat(published).isEqualTo(2);
        assertThat(repository.countByStatus(OutboxEventStatus.PENDING)).isEqualTo(3);
    }

    @Test
    void returnsZeroWhenNoPendingRows() {
        OutboxPoller poller = pollerWith(producerAlwaysSucceeding(), 100);
        assertThat(poller.pollOnce()).isZero();
    }

    private OutboxPoller pollerWith(SendCounter producer, int batchSize) {
        OutboxProcessorProperties properties = new OutboxProcessorProperties();
        properties.setBatchSize(batchSize);
        properties.getRetry().setMaxAttempts(3);
        properties.getTopics().setDefaultTopic("domain-events");
        EventTopicResolver resolver = new EventTopicResolver(properties.getTopics());
        OutboxRelayService relay = new OutboxRelayService(repository, producer.producer(), resolver,
                alertPort, properties, FIXED_CLOCK, sleeper);
        return new OutboxPoller(repository, relay, properties);
    }

    private static OutboxEventEntity pendingEvent() {
        return new OutboxEventEntity(UUID.randomUUID(), "Booking", UUID.randomUUID(),
                "BookingCreated", "{\"redacted\":true}");
    }
}
