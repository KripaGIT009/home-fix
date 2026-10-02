package com.homefix.shared.outbox.kafka;

import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Forwards events that could not be processed to a consumer group's dead-letter topic
 * (Task 6, Requirement 22.6).
 *
 * <p>The dead-letter topic name is derived by suffixing the source topic with {@code .DLT}
 * (e.g. {@code booking.created} → {@code booking.created.DLT}). The original {@code eventId}
 * and the failure reason are attached as headers so operators can trace the poison message.
 *
 * <p><b>The send is awaited.</b> {@link #forward} blocks until the broker acknowledges the
 * dead-letter record, up to {@link #DEFAULT_SEND_TIMEOUT}, and throws if it does not. Both callers
 * acknowledge the source record as soon as {@code forward} returns: {@link IdempotentKafkaConsumer}
 * on the final attempt and the error handler's recoverer. A fire-and-forget send let the offset
 * commit while the dead-letter write was still in flight, or had already failed, and the event was
 * then gone from both topics. Throwing instead keeps the source offset uncommitted, so the
 * container redelivers the record and recovery is attempted again, which is also how Spring's
 * {@code DeadLetterPublishingRecoverer} behaves.
 */
public class DlqForwarder {

    /** Suffix appended to a source topic to form its dead-letter topic. */
    public static final String DLT_SUFFIX = ".DLT";
    public static final String HEADER_EVENT_ID = "eventId";
    public static final String HEADER_DLQ_REASON = "dlqReason";
    public static final String HEADER_ORIGINAL_TOPIC = "originalTopic";
    /**
     * How long {@link #forward} waits for the broker's acknowledgement. Well inside the default
     * {@code max.poll.interval.ms} (5 min), so a stalled broker fails the recovery instead of
     * getting the consumer evicted from its group.
     */
    public static final Duration DEFAULT_SEND_TIMEOUT = Duration.ofSeconds(30);

    private static final Logger log = LoggerFactory.getLogger(DlqForwarder.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final Duration sendTimeout;

    public DlqForwarder(KafkaTemplate<String, String> kafkaTemplate) {
        this(kafkaTemplate, DEFAULT_SEND_TIMEOUT);
    }

    public DlqForwarder(KafkaTemplate<String, String> kafkaTemplate, Duration sendTimeout) {
        this.kafkaTemplate = kafkaTemplate;
        this.sendTimeout = sendTimeout;
    }

    /**
     * Publishes the failed record to {@code <sourceTopic>.DLT}.
     *
     * @param sourceTopic the topic the event was originally consumed from
     * @param key         the record key (preserved for partition affinity)
     * @param eventId     the stable event ID, echoed as a header
     * @param payload     the original event payload
     * @param reason      a human-readable description of why processing failed
     * @throws KafkaException if the broker does not acknowledge the record within the send
     *         timeout, so the caller does not acknowledge the source record
     */
    public void forward(String sourceTopic, String key, String eventId, String payload, String reason) {
        String dltTopic = deadLetterTopicFor(sourceTopic);
        var record = new org.apache.kafka.clients.producer.ProducerRecord<>(dltTopic, null, key, payload);
        if (eventId != null) {
            record.headers().add(new RecordHeader(HEADER_EVENT_ID, eventId.getBytes(StandardCharsets.UTF_8)));
        }
        record.headers().add(new RecordHeader(HEADER_ORIGINAL_TOPIC,
                sourceTopic.getBytes(StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader(HEADER_DLQ_REASON,
                (reason == null ? "unknown" : reason).getBytes(StandardCharsets.UTF_8)));
        try {
            kafkaTemplate.send(record).get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new KafkaException("Interrupted while dead-lettering event " + eventId + " to " + dltTopic, ex);
        } catch (ExecutionException ex) {
            throw new KafkaException("Failed to dead-letter event " + eventId + " to " + dltTopic,
                    ex.getCause() == null ? ex : ex.getCause());
        } catch (TimeoutException ex) {
            throw new KafkaException("Timed out after " + sendTimeout + " dead-lettering event " + eventId
                    + " to " + dltTopic, ex);
        }
        log.warn("Forwarded event {} from topic {} to dead-letter topic {} reason={}",
                eventId, sourceTopic, dltTopic, reason);
    }

    /**
     * Derives the dead-letter topic name for a source topic.
     */
    public static String deadLetterTopicFor(String sourceTopic) {
        return sourceTopic + DLT_SUFFIX;
    }
}
