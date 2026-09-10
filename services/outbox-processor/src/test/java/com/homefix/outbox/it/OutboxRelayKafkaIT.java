package com.homefix.outbox.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import com.homefix.outbox.alert.OutboxAlertPort;
import com.homefix.outbox.config.OutboxProcessorProperties;
import com.homefix.outbox.relay.EventTopicResolver;
import com.homefix.outbox.relay.OutboxRelayService;
import com.homefix.outbox.relay.Sleeper;
import com.homefix.outbox.support.InMemoryOutboxEventRepository;
import com.homefix.shared.outbox.OutboxEventEntity;
import com.homefix.shared.outbox.OutboxEventStatus;
import com.homefix.shared.outbox.kafka.KafkaProducerTemplate;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.SendResult;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.junit.jupiter.SpringExtension;

/**
 * Integration test for the transactional-outbox relay against a <em>real</em> embedded Kafka
 * broker (Task 45; Requirements 22.3, 22.4).
 *
 * <p><b>Scenario.</b> An outbox row is PENDING (its DB commit already happened). We then simulate
 * a Kafka broker outage for the first few publish attempts, verify the relay retries on the
 * exponential-backoff schedule (1&nbsp;s, 2&nbsp;s, 4&nbsp;s, … capped at 60&nbsp;s), let the
 * broker "recover", and assert that:
 * <ul>
 *   <li>the event is durably published to its Kafka topic (we consume it back off the embedded
 *       broker, carrying the stable {@code eventId} header the consumers deduplicate on), and</li>
 *   <li>the outbox row is marked {@link OutboxEventStatus#PUBLISHED}.</li>
 * </ul>
 *
 * <p><b>Why the wrapper.</b> The relay publishes through the shared {@link KafkaProducerTemplate}
 * over a Spring {@link KafkaTemplate}. To make the outage deterministic (rather than sleeping
 * while a broker is genuinely down) we wrap the embedded-broker-backed {@code KafkaTemplate} in a
 * {@link FlakyKafkaTemplate} that fails the first {@code failuresBeforeRecovery} sends and then
 * delegates to the real template — so every recovered send genuinely goes to the embedded broker.
 * The backoff schedule itself is asserted through a recording {@link Sleeper} seam so the test is
 * fast and does not actually wait minutes.
 *
 * <p>This uses {@code @EmbeddedKafka} without a full Spring Boot application context: the relay
 * and its collaborators are the same production classes, wired by hand, and the repository is the
 * in-memory fake also used by the unit tests (JPA persistence of the outbox row is covered by the
 * shared-outbox module's own tests). What is genuinely exercised end-to-end here is the Kafka
 * publish path against a live broker.
 */
@ExtendWith(SpringExtension.class)
@EmbeddedKafka(partitions = 1, topics = {OutboxRelayKafkaIT.TOPIC})
class OutboxRelayKafkaIT {

    static final String TOPIC = "PaymentCompleted";

    @Autowired
    private EmbeddedKafkaBroker broker;

    private final InMemoryOutboxEventRepository repository = new InMemoryOutboxEventRepository();
    private final RecordingSleeper sleeper = new RecordingSleeper();
    private final RecordingAlertPort alertPort = new RecordingAlertPort();

    private Consumer<String, String> consumer;

    @BeforeEach
    void setUp() {
        Map<String, Object> consumerProps = KafkaTestUtils.consumerProps(
                "outbox-relay-it", "true", broker);
        consumerProps.put(org.apache.kafka.clients.consumer.ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class);
        consumerProps.put(org.apache.kafka.clients.consumer.ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class);
        consumer = new org.apache.kafka.clients.consumer.KafkaConsumer<>(consumerProps);
        broker.consumeFromEmbeddedTopics(consumer, TOPIC);
    }

    @AfterEach
    void tearDown() {
        if (consumer != null) {
            consumer.close();
        }
    }

    @Test
    void brokerOutageThenRecovery_publishesToKafkaAndMarksRowPublished() {
        // Two simulated broker failures, then the broker "recovers" on the 3rd attempt.
        KafkaProducerTemplate producer = producerFailingFirst(2);
        OutboxRelayService relay = relayWith(producer, 10);

        String payload = "{\"bookingId\":\"" + UUID.randomUUID() + "\",\"amount\":\"redacted\"}";
        OutboxEventEntity event = new OutboxEventEntity(
                UUID.randomUUID(), "Booking", UUID.randomUUID(), "PaymentCompleted", payload);
        repository.add(event);

        boolean published = relay.relay(event);

        // The relay reports success and the row is PUBLISHED (Requirement 22.3).
        assertThat(published).isTrue();
        OutboxEventEntity stored = repository.findById(event.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(OutboxEventStatus.PUBLISHED);
        assertThat(stored.getPublishedAt()).isNotNull();
        assertThat(alertPort.alerts).isZero();

        // Backoff waited before attempts 2 and 3: exactly 1 s then 2 s (Requirement 22.4).
        assertThat(sleeper.sleeps).containsExactly(1000L, 2000L);

        // The event is genuinely on the topic in the embedded broker, keyed by the aggregate id
        // and carrying the stable eventId header used for downstream dedup.
        ConsumerRecord<String, String> record =
                KafkaTestUtils.getSingleRecord(consumer, TOPIC, Duration.ofSeconds(10));
        assertThat(record.value()).isEqualTo(payload);
        assertThat(record.key()).isEqualTo(event.getAggregateId().toString());
        Header eventId = record.headers().lastHeader(KafkaProducerTemplate.HEADER_EVENT_ID);
        assertThat(eventId).isNotNull();
        assertThat(new String(eventId.value())).isEqualTo(event.getId().toString());
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    /**
     * Builds a {@link KafkaProducerTemplate} over a real embedded-broker {@link KafkaTemplate}
     * wrapped so its first {@code failures} sends fail (simulated broker outage) before it starts
     * delegating to the broker.
     */
    private KafkaProducerTemplate producerFailingFirst(int failures) {
        Map<String, Object> props = new HashMap<>(KafkaTestUtils.producerProps(broker));
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        // Mirror the platform's idempotent/durable producer contract.
        props.putAll(KafkaProducerTemplate.idempotentProducerConfig());
        ProducerFactory<String, String> pf = new DefaultKafkaProducerFactory<>(props);
        KafkaTemplate<String, String> realTemplate = new KafkaTemplate<>(pf);
        return new KafkaProducerTemplate(new FlakyKafkaTemplate(pf, realTemplate, failures));
    }

    private OutboxRelayService relayWith(KafkaProducerTemplate producer, int maxAttempts) {
        OutboxProcessorProperties props = new OutboxProcessorProperties();
        props.getRetry().setInitialInterval(Duration.ofSeconds(1));
        props.getRetry().setMaxInterval(Duration.ofSeconds(60));
        props.getRetry().setMaxAttempts(maxAttempts);
        props.getTopics().setDefaultTopic("domain-events");
        props.getTopics().setMapping(Map.of("PaymentCompleted", TOPIC));
        EventTopicResolver resolver = new EventTopicResolver(props.getTopics());
        return new OutboxRelayService(repository, producer, resolver, alertPort, props,
                Clock.systemUTC(), sleeper);
    }

    /**
     * A {@link KafkaTemplate} decorator that fails the first {@code failuresBeforeRecovery} sends
     * with a returned failed future (as a broker outage would), then delegates to the real
     * embedded-broker template. Only {@code send(ProducerRecord)} and
     * {@code getProducerFactory()} are used by the shared producer, so those are all we override.
     */
    static final class FlakyKafkaTemplate extends KafkaTemplate<String, String> {
        private final KafkaTemplate<String, String> delegate;
        private final int failuresBeforeRecovery;
        private final AtomicInteger sends = new AtomicInteger();

        FlakyKafkaTemplate(ProducerFactory<String, String> pf,
                           KafkaTemplate<String, String> delegate,
                           int failuresBeforeRecovery) {
            super(pf);
            this.delegate = delegate;
            this.failuresBeforeRecovery = failuresBeforeRecovery;
        }

        @Override
        public CompletableFuture<SendResult<String, String>> send(ProducerRecord<String, String> record) {
            if (sends.incrementAndGet() <= failuresBeforeRecovery) {
                return CompletableFuture.failedFuture(
                        new org.apache.kafka.common.errors.TimeoutException(
                                "simulated Kafka broker unavailable"));
            }
            return delegate.send(record);
        }
    }

    /** No-op sleeper recording each backoff delay so the retry schedule can be asserted. */
    static final class RecordingSleeper implements Sleeper {
        final List<Long> sleeps = new ArrayList<>();

        @Override
        public void sleep(long millis) {
            sleeps.add(millis);
        }
    }

    /** Records exhaustion alerts (Requirement 22.4). */
    static final class RecordingAlertPort implements OutboxAlertPort {
        int alerts;

        @Override
        public void alertPublishExhausted(UUID eventId, String topic, int totalAttempts) {
            alerts++;
        }
    }
}
