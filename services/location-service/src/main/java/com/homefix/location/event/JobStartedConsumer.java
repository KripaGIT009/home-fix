package com.homefix.location.event;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homefix.location.service.LocationService;
import com.homefix.shared.outbox.kafka.DlqForwarder;
import com.homefix.shared.outbox.kafka.IdempotentKafkaConsumer;
import com.homefix.shared.outbox.kafka.ProcessedEventRepository;

/**
 * Consumes {@code JobStarted} events and terminates the corresponding Booking's location feed
 * (Requirement 10.5): all active subscriptions are closed and further updates are rejected.
 *
 * <p>Extends the shared {@link IdempotentKafkaConsumer} so a redelivered event is processed at
 * most once per consumer group and a poison message is dead-lettered after the shared retry
 * budget (Task 6). The termination itself is also idempotent in {@link LocationService}, so a
 * duplicate that slips past dedup is harmless.
 */
@Component
public class JobStartedConsumer extends IdempotentKafkaConsumer {

    /** Consumer group for the Location Service's JobStarted subscription. */
    public static final String CONSUMER_GROUP = "location-service.job-started";

    /**
     * Default topic carrying JobStarted events (Requirement 9.5).
     *
     * <p>This must match the name the Outbox Processor publishes under, which is the bare event type
     * {@code JobStarted} (see its {@code homefix.outbox-processor.topics.mapping}). It was previously
     * the hard-coded, non-configurable {@code booking.job-started}, which no producer ever wrote to,
     * so this consumer never received an event and location feeds were never terminated on job
     * start. The listener now reads it from configuration so the two sides can be aligned without a
     * code change.
     */
    public static final String DEFAULT_TOPIC = "JobStarted";

    private static final Logger log = LoggerFactory.getLogger(JobStartedConsumer.class);

    private final LocationService locationService;
    private final ObjectMapper objectMapper;

    public JobStartedConsumer(ProcessedEventRepository processedEventRepository,
                              DlqForwarder dlqForwarder,
                              LocationService locationService,
                              ObjectMapper objectMapper) {
        super(CONSUMER_GROUP, processedEventRepository, dlqForwarder);
        this.locationService = locationService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "${homefix.location.topics.job-started:" + DEFAULT_TOPIC + "}",
            groupId = CONSUMER_GROUP)
    public void onMessage(ConsumerRecord<String, String> record) {
        consume(record);
    }

    @Override
    protected void handle(ConsumerRecord<String, String> record) {
        JobStartedEvent event = parse(record.value());
        if (event.bookingId() == null) {
            // A malformed payload is not retriable; surface it so the base class dead-letters it.
            throw new IllegalArgumentException("JobStarted event missing bookingId: " + record.value());
        }
        log.debug("Terminating location feed for booking {} on JobStarted", event.bookingId());
        locationService.terminate(event.bookingId());
    }

    private JobStartedEvent parse(String payload) {
        try {
            return objectMapper.readValue(payload, JobStartedEvent.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse JobStarted event: " + payload, e);
        }
    }
}
