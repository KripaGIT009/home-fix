package com.homefix.outbox.support;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.homefix.outbox.alert.OutboxAlertPort;
import com.homefix.outbox.relay.Sleeper;
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
 * Shared in-memory doubles for the outbox-processor unit tests: a recording alert port, a
 * no-op/recording sleeper, and helpers that build a {@link KafkaProducerTemplate} over a mocked
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

    /** No-op sleeper that records each backoff delay so the retry schedule can be asserted. */
    public static final class RecordingSleeper implements Sleeper {
        public final List<Long> sleeps = new ArrayList<>();

        @Override
        public void sleep(long millis) {
            sleeps.add(millis);
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

    /** Builds a {@link KafkaProducerTemplate} whose sends always fail. */
    public static SendCounter producerAlwaysFailing() {
        return new SendCounter(Integer.MAX_VALUE, true);
    }

    /** Builds a {@link KafkaProducerTemplate} whose sends always ACK. */
    public static SendCounter producerAlwaysSucceeding() {
        return new SendCounter(0, false);
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

        @SuppressWarnings("unchecked")
        private SendCounter(int failuresBeforeSuccess, boolean alwaysFail) {
            KafkaTemplate<String, String> template = mock(KafkaTemplate.class);
            ProducerFactory<String, String> factory = mock(ProducerFactory.class);
            Map<String, Object> config = new HashMap<>();
            config.put(ProducerConfig.ACKS_CONFIG, "all");
            config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
            when(template.getProducerFactory()).thenReturn(factory);
            when(factory.getConfigurationProperties()).thenReturn(config);

            when(template.send(any(ProducerRecord.class))).thenAnswer(invocation -> {
                ProducerRecord<String, String> record = invocation.getArgument(0);
                lastTopic = record.topic();
                lastKey = record.key();
                sends++;
                if (alwaysFail || sends <= failuresBeforeSuccess) {
                    return CompletableFuture.failedFuture(
                            new IllegalStateException("simulated broker unavailable"));
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
