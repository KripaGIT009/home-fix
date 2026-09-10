package com.homefix.shared.outbox.kafka;

import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;

import java.nio.charset.StandardCharsets;

/**
 * Forwards events that could not be processed to a consumer group's dead-letter topic
 * (Task 6, Requirement 22.6).
 *
 * <p>The dead-letter topic name is derived by suffixing the source topic with {@code .DLT}
 * (e.g. {@code booking.created} → {@code booking.created.DLT}). The original {@code eventId}
 * and the failure reason are attached as headers so operators can trace the poison message.
 */
public class DlqForwarder {

    /** Suffix appended to a source topic to form its dead-letter topic. */
    public static final String DLT_SUFFIX = ".DLT";
    public static final String HEADER_EVENT_ID = "eventId";
    public static final String HEADER_DLQ_REASON = "dlqReason";
    public static final String HEADER_ORIGINAL_TOPIC = "originalTopic";

    private static final Logger log = LoggerFactory.getLogger(DlqForwarder.class);

    private final KafkaTemplate<String, String> kafkaTemplate;

    public DlqForwarder(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    /**
     * Publishes the failed record to {@code <sourceTopic>.DLT}.
     *
     * @param sourceTopic the topic the event was originally consumed from
     * @param key         the record key (preserved for partition affinity)
     * @param eventId     the stable event ID, echoed as a header
     * @param payload     the original event payload
     * @param reason      a human-readable description of why processing failed
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
        kafkaTemplate.send(record);
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
