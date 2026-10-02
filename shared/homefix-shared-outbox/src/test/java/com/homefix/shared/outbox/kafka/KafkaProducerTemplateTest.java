package com.homefix.shared.outbox.kafka;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.SendResult;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link KafkaProducerTemplate}: it enforces the durability contract
 * ({@code acks=all}, {@code enable.idempotence=true}) and stamps the stable {@code eventId}
 * header used by consumers to deduplicate redeliveries (Task 6).
 */
class KafkaProducerTemplateTest {

    private static final String TOPIC = "booking.created";

    @Test
    void idempotentProducerConfigSetsAcksAllAndIdempotence() {
        Map<String, Object> config = KafkaProducerTemplate.idempotentProducerConfig();
        assertThat(config.get(ProducerConfig.ACKS_CONFIG)).isEqualTo("all");
        assertThat(config.get(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG)).isEqualTo(true);
    }

    @Test
    void constructorRejectsTemplateWithoutAcksAll() {
        KafkaTemplate<String, String> template = templateWith(Map.of(
                ProducerConfig.ACKS_CONFIG, "1",
                ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true));

        assertThatThrownBy(() -> new KafkaProducerTemplate(template))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("acks=all");
    }

    @Test
    void constructorRejectsTemplateWithoutIdempotence() {
        KafkaTemplate<String, String> template = templateWith(Map.of(
                ProducerConfig.ACKS_CONFIG, "all",
                ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, false));

        assertThatThrownBy(() -> new KafkaProducerTemplate(template))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("enable.idempotence");
    }

    @Test
    void sendAttachesStableEventIdHeaderAndBlocksForAck() {
        KafkaTemplate<String, String> template = templateWith(Map.of(
                ProducerConfig.ACKS_CONFIG, "all",
                ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true));

        UUID eventId = UUID.randomUUID();
        SendResult<String, String> result = mock(SendResult.class);
        when(result.getRecordMetadata()).thenReturn(
                new RecordMetadata(new TopicPartition(TOPIC, 0), 0L, 0, 0L, 0, 0));
        when(template.send(org.mockito.ArgumentMatchers.<ProducerRecord<String, String>>any()))
                .thenReturn(CompletableFuture.completedFuture(result));

        KafkaProducerTemplate producer = new KafkaProducerTemplate(template);
        producer.send(TOPIC, "booking-1", eventId, "{\"ok\":true}");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<ProducerRecord<String, String>> captor =
                ArgumentCaptor.forClass(ProducerRecord.class);
        verify(template).send(captor.capture());
        ProducerRecord<String, String> sent = captor.getValue();
        assertThat(sent.topic()).isEqualTo(TOPIC);
        assertThat(sent.key()).isEqualTo("booking-1");
        Header header = sent.headers().lastHeader(KafkaProducerTemplate.HEADER_EVENT_ID);
        assertThat(header).isNotNull();
        assertThat(new String(header.value(), StandardCharsets.UTF_8)).isEqualTo(eventId.toString());
    }

    @Test
    void sendWithTimeoutFailsWhenTheAckDoesNotArriveInTime() {
        KafkaTemplate<String, String> template = templateWith(KafkaProducerTemplate.idempotentProducerConfig());
        when(template.send(org.mockito.ArgumentMatchers.<ProducerRecord<String, String>>any()))
                .thenReturn(new CompletableFuture<>()); // never completes
        KafkaProducerTemplate producer = new KafkaProducerTemplate(template);
        UUID eventId = UUID.randomUUID();

        assertThatThrownBy(() -> producer.send(TOPIC, "booking-1", eventId, "{}", java.time.Duration.ofMillis(50)))
                .isInstanceOf(KafkaProducerTemplate.KafkaPublishException.class)
                .hasMessageContaining("Timed out")
                .hasMessageContaining(eventId.toString())
                .hasCauseInstanceOf(java.util.concurrent.TimeoutException.class);
    }

    @Test
    void sendWithTimeoutReturnsTheAckWhenItArrives() {
        KafkaTemplate<String, String> template = templateWith(KafkaProducerTemplate.idempotentProducerConfig());
        @SuppressWarnings("unchecked")
        SendResult<String, String> result = mock(SendResult.class);
        when(result.getRecordMetadata()).thenReturn(
                new RecordMetadata(new TopicPartition(TOPIC, 0), 0L, 0, 0L, 0, 0));
        when(template.send(org.mockito.ArgumentMatchers.<ProducerRecord<String, String>>any()))
                .thenReturn(CompletableFuture.completedFuture(result));

        KafkaProducerTemplate producer = new KafkaProducerTemplate(template);

        assertThat(producer.send(TOPIC, "booking-1", UUID.randomUUID(), "{}", java.time.Duration.ofSeconds(5)))
                .isSameAs(result);
    }

    @Test
    void exposesTheProducersMaxBlockTimeWhateverTypeItIsConfiguredAs() {
        Map<String, Object> config = new HashMap<>(KafkaProducerTemplate.idempotentProducerConfig());
        config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, "10000");
        assertThat(new KafkaProducerTemplate(templateWith(config)).maxBlockTime())
                .isEqualTo(java.time.Duration.ofSeconds(10));

        config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 2_500);
        assertThat(new KafkaProducerTemplate(templateWith(config)).maxBlockTime())
                .isEqualTo(java.time.Duration.ofMillis(2_500));
    }

    @Test
    void maxBlockTimeDefaultsToKafkasOwnDefaultWhenUnset() {
        KafkaProducerTemplate producer =
                new KafkaProducerTemplate(templateWith(KafkaProducerTemplate.idempotentProducerConfig()));

        assertThat(producer.maxBlockTime()).isEqualTo(java.time.Duration.ofSeconds(60));
    }

    @SuppressWarnings("unchecked")
    private static KafkaTemplate<String, String> templateWith(Map<String, Object> producerConfig) {
        KafkaTemplate<String, String> template = mock(KafkaTemplate.class);
        ProducerFactory<String, String> factory = mock(ProducerFactory.class);
        when(template.getProducerFactory()).thenReturn(factory);
        when(factory.getConfigurationProperties()).thenReturn(new HashMap<>(producerConfig));
        return template;
    }
}
