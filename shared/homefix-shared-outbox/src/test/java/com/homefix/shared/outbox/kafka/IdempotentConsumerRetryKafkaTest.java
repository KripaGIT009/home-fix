package com.homefix.shared.outbox.kafka;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * End-to-end check of the consumer retry contract against a real embedded broker
 * (Requirements 22.5, 22.6), wired exactly as a service is: Spring Boot's auto-configured
 * listener-container factory plus this module's auto-configuration, a real {@code @KafkaListener}
 * on an {@link IdempotentKafkaConsumer} subclass, and the real JPA {@code processed_event} table
 * (on H2).
 *
 * <ul>
 *   <li>A transient failure is retried by the container with the delivery-attempt header counting
 *       1, 2, 3, the retry delay elapses between attempts, and the listener container is paused
 *       (not its thread asleep) while it does.</li>
 *   <li>A poison event is handled exactly {@value IdempotentKafkaConsumer#MAX_RETRIES} times, lands
 *       on {@code <topic>.DLT} with the usual headers, and the next event on the topic is still
 *       processed.</li>
 * </ul>
 */
@SpringBootTest(classes = IdempotentConsumerRetryKafkaTest.Config.class, properties = {
        "spring.kafka.consumer.auto-offset-reset=earliest",
        "spring.kafka.consumer.key-deserializer=org.apache.kafka.common.serialization.StringDeserializer",
        "spring.kafka.consumer.value-deserializer=org.apache.kafka.common.serialization.StringDeserializer",
        "spring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.StringSerializer",
        "spring.kafka.producer.value-serializer=org.apache.kafka.common.serialization.StringSerializer",
        "spring.kafka.producer.acks=all",
        "spring.kafka.producer.properties.enable.idempotence=true",
        "homefix.outbox.consumer.retry-delay=PT1.5S",
        "spring.datasource.url=jdbc:h2:mem:consumer_retry;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.hbm2ddl.create_namespaces=true",
        "logging.level.org.apache.kafka=warn"
})
@EmbeddedKafka(partitions = 1, bootstrapServersProperty = "spring.kafka.bootstrap-servers", topics = {
        IdempotentConsumerRetryKafkaTest.TRANSIENT_TOPIC,
        IdempotentConsumerRetryKafkaTest.POISON_TOPIC,
        IdempotentConsumerRetryKafkaTest.POISON_TOPIC + DlqForwarder.DLT_SUFFIX
})
class IdempotentConsumerRetryKafkaTest {

    static final String TRANSIENT_TOPIC = "it.transient";
    static final String POISON_TOPIC = "it.poison";
    static final long RETRY_DELAY_MS = 1_500L;

    @Autowired
    private EmbeddedKafkaBroker broker;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private ProcessedEventRepository processedEvents;

    @Autowired
    private KafkaListenerEndpointRegistry registry;

    @Autowired
    private TransientConsumer transientConsumer;

    @Autowired
    private PoisonConsumer poisonConsumer;

    @Test
    void transientFailureIsRetriedByThePausedContainerThenProcessed() {
        UUID eventId = UUID.randomUUID();
        publish(TRANSIENT_TOPIC, eventId, "fail-twice");

        // After the first failure the container is paused for the retry delay instead of the
        // listener thread sleeping inside consume().
        await().atMost(15, TimeUnit.SECONDS).until(() -> transientConsumer.calls.get() >= 1);
        await().atMost(RETRY_DELAY_MS, TimeUnit.MILLISECONDS).pollInterval(Duration.ofMillis(20))
                .until(() -> registry.getListenerContainer(TransientConsumer.ID).isPauseRequested());

        await().atMost(20, TimeUnit.SECONDS).until(() ->
                processedEvents.existsByConsumerGroupAndEventId(TransientConsumer.GROUP, eventId));

        assertThat(transientConsumer.calls.get()).isEqualTo(IdempotentKafkaConsumer.MAX_RETRIES);
        assertThat(transientConsumer.deliveryAttempts).containsExactly(1, 2, 3);
        List<Long> times = transientConsumer.callNanos;
        for (int i = 1; i < times.size(); i++) {
            long gapMillis = TimeUnit.NANOSECONDS.toMillis(times.get(i) - times.get(i - 1));
            assertThat(gapMillis).as("gap before attempt %d", i + 1).isGreaterThanOrEqualTo(RETRY_DELAY_MS - 100);
        }
    }

    @Test
    void poisonEventIsDeadLetteredAfterThreeAttemptsAndTheTopicKeepsFlowing() {
        UUID poisonId = UUID.randomUUID();
        UUID healthyId = UUID.randomUUID();
        try (Consumer<String, String> dlt = newConsumer("it-dlt-reader")) {
            broker.consumeFromAnEmbeddedTopic(dlt, POISON_TOPIC + DlqForwarder.DLT_SUFFIX);

            publish(POISON_TOPIC, poisonId, "poison");

            ConsumerRecord<String, String> dead = KafkaTestUtils.getSingleRecord(
                    dlt, POISON_TOPIC + DlqForwarder.DLT_SUFFIX, Duration.ofSeconds(30));
            assertThat(dead.value()).isEqualTo("poison");
            assertThat(header(dead, DlqForwarder.HEADER_EVENT_ID)).isEqualTo(poisonId.toString());
            assertThat(header(dead, DlqForwarder.HEADER_ORIGINAL_TOPIC)).isEqualTo(POISON_TOPIC);
            assertThat(header(dead, DlqForwarder.HEADER_DLQ_REASON))
                    .isEqualTo("java.lang.IllegalStateException: poison payload");
        }
        assertThat(poisonConsumer.poisonCalls.get()).isEqualTo(IdempotentKafkaConsumer.MAX_RETRIES);
        assertThat(processedEvents.existsByConsumerGroupAndEventId(PoisonConsumer.GROUP, poisonId)).isFalse();

        publish(POISON_TOPIC, healthyId, "healthy");
        await().atMost(20, TimeUnit.SECONDS).until(() ->
                processedEvents.existsByConsumerGroupAndEventId(PoisonConsumer.GROUP, healthyId));
        // The poison event was acknowledged after dead-lettering, never redelivered again.
        assertThat(poisonConsumer.poisonCalls.get()).isEqualTo(IdempotentKafkaConsumer.MAX_RETRIES);
    }

    // ---------------------------------------------------------------------

    private void publish(String topic, UUID eventId, String value) {
        ProducerRecord<String, String> record = new ProducerRecord<>(topic, "key-" + eventId, value);
        record.headers().add(new RecordHeader(KafkaProducerTemplate.HEADER_EVENT_ID,
                eventId.toString().getBytes(StandardCharsets.UTF_8)));
        kafkaTemplate.send(record);
        kafkaTemplate.flush();
    }

    private Consumer<String, String> newConsumer(String group) {
        Map<String, Object> props = new HashMap<>(KafkaTestUtils.consumerProps(group, "true", broker));
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        return new KafkaConsumer<>(props);
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        var h = record.headers().lastHeader(name);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = ProcessedEventEntity.class)
    @EnableJpaRepositories(basePackageClasses = ProcessedEventRepository.class)
    @Import({TransientConsumer.class, PoisonConsumer.class})
    static class Config {
    }

    /** Fails its first two calls, then succeeds; records each call's time and attempt header. */
    static class TransientConsumer extends IdempotentKafkaConsumer {
        static final String ID = "it-transient-listener";
        static final String GROUP = "it-transient";

        final AtomicInteger calls = new AtomicInteger();
        final List<Integer> deliveryAttempts = new CopyOnWriteArrayList<>();
        final List<Long> callNanos = new CopyOnWriteArrayList<>();

        TransientConsumer(ProcessedEventRepository repository, DlqForwarder dlqForwarder) {
            super(GROUP, repository, dlqForwarder);
        }

        @KafkaListener(id = ID, topics = TRANSIENT_TOPIC, groupId = GROUP)
        void onMessage(ConsumerRecord<String, String> record) {
            consume(record);
        }

        @Override
        protected void handle(ConsumerRecord<String, String> record) {
            callNanos.add(System.nanoTime());
            deliveryAttempts.add(extractDeliveryAttempt(record));
            if (calls.incrementAndGet() <= 2) {
                throw new IllegalStateException("transient downstream failure");
            }
        }
    }

    /** Always fails a record whose value is {@code poison}; processes anything else. */
    static class PoisonConsumer extends IdempotentKafkaConsumer {
        static final String GROUP = "it-poison";

        final AtomicInteger poisonCalls = new AtomicInteger();

        PoisonConsumer(ProcessedEventRepository repository, DlqForwarder dlqForwarder) {
            super(GROUP, repository, dlqForwarder);
        }

        @KafkaListener(id = "it-poison-listener", topics = POISON_TOPIC, groupId = GROUP)
        void onMessage(ConsumerRecord<String, String> record) {
            consume(record);
        }

        @Override
        protected void handle(ConsumerRecord<String, String> record) {
            if ("poison".equals(record.value())) {
                poisonCalls.incrementAndGet();
                throw new IllegalStateException("poison payload");
            }
        }
    }
}
