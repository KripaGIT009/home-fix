package com.homefix.shared.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Writes an outbox row within the caller's existing {@code @Transactional} boundary so the
 * event and the business state change commit atomically (Task 6, Requirement 22.2).
 *
 * <p>This class does <em>not</em> talk to Kafka. It only persists a {@link OutboxEventStatus#PENDING}
 * row; a separate Outbox Processor relays it. Because {@link #publish} uses
 * {@link Propagation#MANDATORY}, calling it outside an active transaction throws — this is a
 * deliberate guard so callers cannot accidentally break the atomicity guarantee.
 */
public class OutboxEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxEventPublisher.class);

    private final OutboxEventRepository repository;
    private final ObjectMapper objectMapper;

    public OutboxEventPublisher(OutboxEventRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /**
     * Serialises {@code payload} to JSON and writes a PENDING outbox row inside the caller's
     * transaction.
     *
     * @return the generated event ID (also the outbox row primary key and the Kafka
     *         {@code eventId} the consumer will deduplicate on)
     * @throws IllegalStateException if invoked outside an active transaction
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID publish(String aggregateType, UUID aggregateId, String eventType, Object payload) {
        String json = serialise(payload);
        OutboxEventEntity entity = OutboxEventEntity.newEvent(aggregateType, aggregateId, eventType, json);
        repository.save(entity);
        log.debug("Wrote outbox event {} type={} aggregate={}:{}",
                entity.getId(), eventType, aggregateType, aggregateId);
        return entity.getId();
    }

    private String serialise(Object payload) {
        if (payload instanceof String s) {
            return s;
        }
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Unable to serialise outbox payload for Kafka relay", e);
        }
    }
}
