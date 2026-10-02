package com.homefix.outbox.it;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.homefix.shared.outbox.OutboxEventEntity;
import com.homefix.shared.outbox.OutboxEventRepository;
import com.homefix.shared.outbox.OutboxEventStatus;
import com.homefix.shared.outbox.kafka.KafkaProducerTemplate;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Boots the real Outbox Processor application — its own configuration, the shipped
 * {@code application.yml} topic mapping, the scheduled poller — against H2 and an embedded Kafka
 * broker, and checks the production wiring end to end: a PENDING row written to the outbox is
 * claimed by the scheduled poll, published to its mapped topic with the {@code eventId} header,
 * and marked PUBLISHED.
 *
 * <p>Uses a {@code ComplaintCreated} row, so it also proves complaint events reach their own topic
 * rather than the {@code domain-events} catch-all.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.datasource.url=jdbc:h2:mem:outbox_app;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.hbm2ddl.create_namespaces=true",
        "spring.jpa.database-platform=com.homefix.outbox.support.SkipLockedH2Dialect",
        "homefix.outbox-processor.poll-interval=PT0.2S",
        "logging.level.org.apache.kafka=warn"
})
@EmbeddedKafka(partitions = 1, bootstrapServersProperty = "spring.kafka.bootstrap-servers",
        topics = {OutboxProcessorApplicationKafkaTest.TOPIC})
class OutboxProcessorApplicationKafkaTest {

    static final String TOPIC = "ComplaintCreated";

    @Autowired
    private OutboxEventRepository repository;

    @Autowired
    private EmbeddedKafkaBroker broker;

    @Test
    void scheduledPollerClaimsPublishesAndMarksTheRowPublished() {
        String payload = "{\"complaintId\":\"" + UUID.randomUUID() + "\"}";
        OutboxEventEntity row = repository.save(
                OutboxEventEntity.newEvent("Complaint", UUID.randomUUID(), "ComplaintCreated", payload));

        try (Consumer<String, String> consumer = newConsumer()) {
            broker.consumeFromAnEmbeddedTopic(consumer, TOPIC);
            ConsumerRecord<String, String> record = KafkaTestUtils.getSingleRecord(consumer, TOPIC, Duration.ofSeconds(30));
            assertThat(record.value()).isEqualTo(payload);
            assertThat(record.key()).isEqualTo(row.getAggregateId().toString());
            assertThat(new String(record.headers().lastHeader(KafkaProducerTemplate.HEADER_EVENT_ID).value()))
                    .isEqualTo(row.getId().toString());
        }

        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
            OutboxEventEntity stored = repository.findById(row.getId()).orElseThrow();
            assertThat(stored.getStatus()).isEqualTo(OutboxEventStatus.PUBLISHED);
            assertThat(stored.getNextAttemptAt()).isNull();
            assertThat(stored.getRetryCount()).isZero();
        });
    }

    private Consumer<String, String> newConsumer() {
        Map<String, Object> props = new HashMap<>(KafkaTestUtils.consumerProps("outbox-app-test", "true", broker));
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        return new KafkaConsumer<>(props);
    }
}
