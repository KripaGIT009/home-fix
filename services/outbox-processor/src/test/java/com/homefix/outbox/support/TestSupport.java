package com.homefix.outbox.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.homefix.outbox.alert.OutboxAlertPort;
import com.homefix.shared.outbox.kafka.KafkaProducerTemplate;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.SendResult;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Shared in-memory doubles for the outbox-processor unit tests: a recording alert port, a clock
 * the test can move forward, and helpers that build a {@link KafkaProducerTemplate} over a mocked
 * {@link KafkaTemplate} whose send either ACKs or fails a configurable number of times.
 */
public final class TestSupport {

    private TestSupport() {
    }

    /** Records exhaustion alerts (Requirement 22.4) for assertions. */
    public static final class RecordingAlertPort implements OutboxAlertPort {
        public int alerts;
        public UUID lastEventId;
        public String lastTopic;
        public int lastTotalAttempts;

        @Override
        public void alertPublishExhausted(UUID eventId, String topic, int totalAttempts) {
            alerts++;
            lastEventId = eventId;
            lastTopic = topic;
            lastTotalAttempts = totalAttempts;
        }
    }

    /** A clock that stands still until the test advances it, for asserting persisted schedules. */
    public static final class MutableClock extends Clock {
        private volatile Instant now;

        public MutableClock(Instant start) {
            this.now = start;
        }

        public void advance(Duration by) {
            now = now.plus(by);
        }

        public void set(Instant instant) {
            now = instant;
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    /**
     * Builds a {@link KafkaProducerTemplate} whose underlying mocked {@link KafkaTemplate} fails
     * the first {@code failuresBeforeSuccess} sends (throwing on the returned future) and ACKs
     * thereafter. Records the number of send invocations via {@link SendCounter}.
     */
    public static SendCounter producerFailingThenSucceeding(int failuresBeforeSuccess) {
        return new SendCounter(failuresBeforeSuccess, false);
    }

    /**
     * Builds a {@link KafkaProducerTemplate} whose sends always fail with an error the relay treats
     * as non-retriable, so every failure spends an attempt.
     */
    public static SendCounter producerAlwaysFailing() {
        return new SendCounter(Integer.MAX_VALUE, true);
    }

    /** Builds a {@link KafkaProducerTemplate} whose sends always fail with {@code failure}. */
    public static SendCounter producerAlwaysFailingWith(Throwable failure) {
        return new SendCounter(Integer.MAX_VALUE, true, failure, Map.of());
    }

    /** Builds a {@link KafkaProducerTemplate} whose sends always ACK. */
    public static SendCounter producerAlwaysSucceeding() {
        return new SendCounter(0, false);
    }

    /** As {@link #producerAlwaysSucceeding()}, with extra producer config (e.g. max.block.ms). */
    public static SendCounter producerAlwaysSucceeding(Map<String, Object> extraConfig) {
        return new SendCounter(0, false, null, extraConfig);
    }

    /**
     * Wraps a mocked {@link KafkaTemplate} and the resulting {@link KafkaProducerTemplate},
     * counting the send calls actually made through the template.
     */
    public static final class SendCounter {
        private final KafkaProducerTemplate producer;
        public int sends;
        public String lastTopic;
        public String lastKey;

        private SendCounter(int failuresBeforeSuccess, boolean alwaysFail) {
            this(failuresBeforeSuccess, alwaysFail, null, Map.of());
        }

        @SuppressWarnings("unchecked")
        private SendCounter(int failuresBeforeSuccess, boolean alwaysFail, Throwable failure,
                            Map<String, Object> extraConfig) {
            KafkaTemplate<String, String> template = mock(KafkaTemplate.class);
            ProducerFactory<String, String> factory = mock(ProducerFactory.class);
            Map<String, Object> config = new HashMap<>();
            config.put(ProducerConfig.ACKS_CONFIG, "all");
            config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
            config.putAll(extraConfig);
            when(template.getProducerFactory()).thenReturn(factory);
            when(factory.getConfigurationProperties()).thenReturn(config);

            when(template.send(any(ProducerRecord.class))).thenAnswer(invocation -> {
                ProducerRecord<String, String> record = invocation.getArgument(0);
                lastTopic = record.topic();
                lastKey = record.key();
                sends++;
                if (alwaysFail || sends <= failuresBeforeSuccess) {
                    return CompletableFuture.failedFuture(failure != null
                            ? failure
                            : new IllegalStateException("simulated broker unavailable"));
                }
                SendResult<String, String> result = mock(SendResult.class);
                when(result.getRecordMetadata()).thenReturn(
                        new RecordMetadata(new TopicPartition(record.topic(), 0), 0L, 0, 0L, 0, 0));
                return CompletableFuture.completedFuture(result);
            });

            this.producer = new KafkaProducerTemplate(template);
        }

        public KafkaProducerTemplate producer() {
            return producer;
        }
    }
}
