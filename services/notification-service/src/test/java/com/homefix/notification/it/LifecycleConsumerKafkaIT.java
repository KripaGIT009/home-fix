package com.homefix.notification.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.homefix.notification.consumer.LifecycleEventConsumer;
import com.homefix.notification.delivery.NotificationDeliveryService;
import com.homefix.notification.domain.NotificationEvent;
import com.homefix.shared.outbox.kafka.DlqForwarder;
import com.homefix.shared.outbox.kafka.KafkaProducerTemplate;
import com.homefix.shared.outbox.kafka.ProcessedEventRepository;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * End-to-end integration tests for the Notification Service's Kafka consume path against a
 * <em>real</em> embedded broker (Task 45; Requirements 22.5, 22.6).
 *
 * <p>The full Spring context boots on an in-memory H2 database with the embedded broker's
 * bootstrap servers, so the genuine pipeline runs: a real {@code @KafkaListener} →
 * {@link LifecycleEventConsumer} (the shared {@code IdempotentKafkaConsumer} providing dedup,
 * bounded retry, and dead-lettering, backed by the real {@code processed_event} JPA table) →
 * {@link NotificationDeliveryService}. Only the terminal delivery service is a mock, so we can
 * (a) count exactly how many dispatches happen and (b) force processing failures on demand — the
 * dedup and DLQ machinery under test is entirely real.
 *
 * <p><b>Idempotent consumption (22.5).</b> The same event redelivered to the same consumer group
 * results in exactly one dispatch — the second delivery is skipped against the persisted
 * {@code (consumerGroup, eventId)} record.
 *
 * <p><b>Dead-letter after 3 failures (22.6).</b> An event whose processing fails on every one of
 * the 3 attempts is forwarded to the {@code <topic>.DLT} dead-letter topic, and a subsequent,
 * healthy event on the same topic is still processed — the consumer does not wedge.
 */
@SpringBootTest
@ActiveProfiles("it")
@Import(LifecycleConsumerKafkaIT.DlqTestConfig.class)
@EmbeddedKafka(partitions = 1, topics = {
        LifecycleConsumerKafkaIT.BOOKING_CREATED,
        LifecycleConsumerKafkaIT.BOOKING_CREATED + ".DLT"
})
class LifecycleConsumerKafkaIT {

    static final String BOOKING_CREATED = "BookingCreated";

    /**
     * The shared {@code DlqForwarder} bean is auto-configured {@code @ConditionalOnBean(KafkaTemplate)};
     * that condition can evaluate before the embedded-broker {@code KafkaTemplate} is registered
     * under the test bootstrapper, leaving the consumer without its forwarder. Declaring it here
     * from the autowired {@link KafkaTemplate} guarantees the real forwarder is wired for the test.
     */
    @TestConfiguration
    static class DlqTestConfig {
        @Bean
        DlqForwarder dlqForwarder(KafkaTemplate<String, String> kafkaTemplate) {
            return new DlqForwarder(kafkaTemplate);
        }

        // The four channel adapters self-select via @ConditionalOnMissingBean, whose evaluation
        // order is unreliable under the test bootstrapper. Since this test mocks the delivery
        // service (the channels are never exercised here), supply trivial no-op port beans so the
        // ChannelDispatcher — a mandatory context bean — wires deterministically.
        @Bean
        com.homefix.notification.channel.SmsPort smsPort() {
            return (mobileNumber, message) -> { };
        }

        @Bean
        com.homefix.notification.channel.EmailPort emailPort() {
            return (emailAddress, subject, body) -> { };
        }

        @Bean
        com.homefix.notification.channel.PushPort pushPort() {
            return (deviceToken, title, body) -> { };
        }

        @Bean
        com.homefix.notification.channel.InAppPort inAppPort() {
            return (userId, title, body) -> { };
        }
    }

    @Autowired
    private EmbeddedKafkaBroker broker;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    // The terminal delivery step is mocked so we can count dispatches and force failures; every
    // stage before it (listener, dedup, retry, DLQ) is the real production code.
    @MockBean
    private NotificationDeliveryService deliveryService;

    private Consumer<String, String> dltConsumer;

    @BeforeEach
    void setUp() {
        processedEventRepository.deleteAll();
    }

    @AfterEach
    void tearDown() {
        if (dltConsumer != null) {
            dltConsumer.close();
        }
    }

    // ---------------------------------------------------------------------
    // Scenario: idempotent consumption (Requirement 22.5)
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("Redelivering the same event to the Notification Service dispatches only one notification")
    void redeliveredEvent_isDispatchedExactlyOnce() {
        doNothing().when(deliveryService).dispatch(any(NotificationEvent.class));

        UUID eventId = UUID.randomUUID();
        UUID recipient = UUID.randomUUID();
        String payload = payloadFor(recipient);

        // First delivery is processed and recorded against (consumerGroup, eventId).
        publish(BOOKING_CREATED, eventId, recipient.toString(), payload);
        verify(deliveryService, timeout(10_000).times(1)).dispatch(any(NotificationEvent.class));
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(processedEventRepository.existsByConsumerGroupAndEventId(
                        LifecycleEventConsumer.CONSUMER_GROUP, eventId)).isTrue());

        // Redeliver the identical event (same eventId) — a duplicate the broker or a retry may send.
        publish(BOOKING_CREATED, eventId, recipient.toString(), payload);

        // No second dispatch: the duplicate is discarded against the persisted event id (22.5).
        // Give the listener time to observe and skip the redelivery before asserting the count.
        await().during(3, TimeUnit.SECONDS).atMost(6, TimeUnit.SECONDS).untilAsserted(() ->
                verify(deliveryService, times(1)).dispatch(any(NotificationEvent.class)));
    }

    // ---------------------------------------------------------------------
    // Scenario: dead-letter after 3 failures, processing continues (Requirement 22.6)
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("An event failing all 3 attempts lands in the dead-letter topic and processing continues")
    void eventFailingThreeTimes_isDeadLetteredAndProcessingContinues() {
        UUID poisonEventId = UUID.randomUUID();
        UUID healthyEventId = UUID.randomUUID();
        UUID recipient = UUID.randomUUID();

        // The poison event fails on every attempt; the healthy event succeeds.
        doThrow(new RuntimeException("simulated downstream failure"))
                .when(deliveryService).dispatch(any(NotificationEvent.class));

        dltConsumer = newConsumer("dlt-it");
        broker.consumeFromAnEmbeddedTopic(dltConsumer, BOOKING_CREATED + ".DLT");

        // Publish the poison event: 3 attempts × (real retry) then forwarded to the DLT.
        publish(BOOKING_CREATED, poisonEventId, recipient.toString(), payloadFor(recipient));

        // The failed event lands on <topic>.DLT with its original eventId + failure headers.
        // The shared consumer retries 3× with a 5 s delay between attempts, so allow ample time.
        ConsumerRecord<String, String> dlt =
                KafkaTestUtils.getSingleRecord(dltConsumer, BOOKING_CREATED + ".DLT", Duration.ofSeconds(45));
        assertThat(dlt).isNotNull();
        assertThat(header(dlt, DlqForwarder.HEADER_EVENT_ID)).isEqualTo(poisonEventId.toString());
        assertThat(header(dlt, DlqForwarder.HEADER_ORIGINAL_TOPIC)).isEqualTo(BOOKING_CREATED);
        assertThat(header(dlt, DlqForwarder.HEADER_DLQ_REASON)).isNotBlank();

        // Dispatch was attempted the full 3 times for the poison event (Requirement 22.6).
        verify(deliveryService, times(3)).dispatch(any(NotificationEvent.class));

        // Processing continues: a healthy event on the same topic is still consumed and dispatched.
        doNothing().when(deliveryService).dispatch(any(NotificationEvent.class));
        publish(BOOKING_CREATED, healthyEventId, recipient.toString(), payloadFor(recipient));
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(processedEventRepository.existsByConsumerGroupAndEventId(
                        LifecycleEventConsumer.CONSUMER_GROUP, healthyEventId)).isTrue());
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private void publish(String topic, UUID eventId, String key, String payload) {
        var record = new org.apache.kafka.clients.producer.ProducerRecord<>(topic, null, key, payload);
        record.headers().add(new org.apache.kafka.common.header.internals.RecordHeader(
                KafkaProducerTemplate.HEADER_EVENT_ID,
                eventId.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        kafkaTemplate.send(record);
        kafkaTemplate.flush();
    }

    private Consumer<String, String> newConsumer(String group) {
        Map<String, Object> props = new HashMap<>(KafkaTestUtils.consumerProps(group, "true", broker));
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        return new org.apache.kafka.clients.consumer.KafkaConsumer<>(props);
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        var h = record.headers().lastHeader(name);
        return h == null ? null : new String(h.value(), java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String payloadFor(UUID recipient) {
        return "{\"bookingId\":\"" + UUID.randomUUID() + "\","
                + "\"bookingReference\":\"HF-IT-0001\","
                + "\"customerId\":\"" + recipient + "\","
                + "\"mobileNumber\":\"+15551234567\","
                + "\"emailAddress\":\"it@example.com\","
                + "\"deviceToken\":\"device-token-it\"}";
    }
}
