package com.homefix.shared.outbox.kafka;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * Thin wrapper over {@link KafkaTemplate} that stamps every record with a stable
 * {@code eventId} header and enforces the reliability contract the platform relies on
 * (Task 6): the underlying producer must run with {@code acks=all} and
 * {@code enable.idempotence=true} so retries never produce duplicate broker-side writes.
 *
 * <p>The {@code eventId} header is what downstream {@code IdempotentKafkaConsumer}s
 * deduplicate on, so it must be identical across producer retries — {@link #send} always
 * carries the caller-supplied ID rather than generating a new one per attempt.
 */
public class KafkaProducerTemplate {

    /** Header key carrying the stable event identifier used for consumer-side idempotency. */
    public static final String HEADER_EVENT_ID = "eventId";

    private static final Logger log = LoggerFactory.getLogger(KafkaProducerTemplate.class);

    private final KafkaTemplate<String, String> kafkaTemplate;

    public KafkaProducerTemplate(KafkaTemplate<String, String> kafkaTemplate) {
        verifyIdempotentConfig(kafkaTemplate);
        this.kafkaTemplate = kafkaTemplate;
    }

    /**
     * Returns the producer configuration a caller must use to satisfy the idempotency and
     * durability guarantees. Callers building their own {@code ProducerFactory} should merge
     * these into their config map.
     */
    public static Map<String, Object> idempotentProducerConfig() {
        return Map.of(
                ProducerConfig.ACKS_CONFIG, "all",
                ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true,
                ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5,
                ProducerConfig.RETRIES_CONFIG, Integer.MAX_VALUE
        );
    }

    /**
     * Publishes {@code payload} to {@code topic} keyed by {@code key}, attaching the given
     * stable {@code eventId} as a record header. Blocks until the broker acknowledges (acks=all)
     * so a returned future completion means the write is durably committed.
     */
    public SendResult<String, String> send(String topic, String key, UUID eventId, String payload) {
        var record = new org.apache.kafka.clients.producer.ProducerRecord<>(topic, null, key, payload);
        record.headers().add(new RecordHeader(HEADER_EVENT_ID,
                eventId.toString().getBytes(StandardCharsets.UTF_8)));
        CompletableFuture<SendResult<String, String>> future = kafkaTemplate.send(record);
        try {
            SendResult<String, String> result = future.get();
            log.debug("Published event {} to topic {} partition {} offset {}",
                    eventId, topic,
                    result.getRecordMetadata().partition(),
                    result.getRecordMetadata().offset());
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new KafkaPublishException("Interrupted publishing event " + eventId + " to " + topic, e);
        } catch (ExecutionException e) {
            throw new KafkaPublishException("Failed publishing event " + eventId + " to " + topic, e.getCause());
        }
    }

    private static void verifyIdempotentConfig(KafkaTemplate<String, String> template) {
        Map<String, Object> config = template.getProducerFactory().getConfigurationProperties();
        Object acks = config.get(ProducerConfig.ACKS_CONFIG);
        Object idempotence = config.get(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG);
        if (!"all".equals(acks)) {
            throw new IllegalStateException(
                    "KafkaProducerTemplate requires acks=all for durability; got acks=" + acks);
        }
        if (!Boolean.TRUE.equals(idempotence) && !"true".equals(String.valueOf(idempotence))) {
            throw new IllegalStateException(
                    "KafkaProducerTemplate requires enable.idempotence=true; got " + idempotence);
        }
    }

    /** Unchecked wrapper so callers are not forced to handle checked send failures. */
    public static class KafkaPublishException extends RuntimeException {
        public KafkaPublishException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
