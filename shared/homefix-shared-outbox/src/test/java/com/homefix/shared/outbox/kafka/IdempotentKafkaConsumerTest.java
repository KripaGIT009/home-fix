package com.homefix.shared.outbox.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link IdempotentKafkaConsumer}: duplicate-event skipping (Requirement 22.5)
 * and dead-letter forwarding after 3 failed retries (Requirement 22.6).
 */
class IdempotentKafkaConsumerTest {

    private static final String GROUP = "dispatch-engine";
    private static final String TOPIC = "booking.created";

    private FakeProcessedEventRepository processedEvents;
    private DlqForwarder dlqForwarder;

    @BeforeEach
    void setUp() {
        processedEvents = new FakeProcessedEventRepository();
        dlqForwarder = mock(DlqForwarder.class);
    }

    private ConsumerRecord<String, String> recordWithEventId(UUID eventId) {
        ConsumerRecord<String, String> record =
                new ConsumerRecord<>(TOPIC, 0, 0L, "booking-1", "{\"payload\":true}");
        record.headers().add(new RecordHeader(KafkaProducerTemplate.HEADER_EVENT_ID,
                eventId.toString().getBytes(StandardCharsets.UTF_8)));
        return record;
    }

    @Test
    void processesEventOnceThenSkipsRedelivery() {
        UUID eventId = UUID.randomUUID();
        AtomicInteger handleCount = new AtomicInteger();
        TestConsumer consumer = TestConsumer.create(processedEvents, dlqForwarder,
                rec -> handleCount.incrementAndGet());

        // First delivery: handled and persisted.
        consumer.consume(recordWithEventId(eventId));
        // Redelivery of the same event ID: must be skipped.
        consumer.consume(recordWithEventId(eventId));

        assertThat(handleCount.get()).isEqualTo(1);
        assertThat(processedEvents.existsByConsumerGroupAndEventId(GROUP, eventId)).isTrue();
        verify(dlqForwarder, never()).forward(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void distinctEventIdsAreEachProcessed() {
        AtomicInteger handleCount = new AtomicInteger();
        TestConsumer consumer = TestConsumer.create(processedEvents, dlqForwarder,
                rec -> handleCount.incrementAndGet());

        consumer.consume(recordWithEventId(UUID.randomUUID()));
        consumer.consume(recordWithEventId(UUID.randomUUID()));

        assertThat(handleCount.get()).isEqualTo(2);
    }

    @Test
    void forwardsToDlqAfterThreeFailedRetries() {
        UUID eventId = UUID.randomUUID();
        AtomicInteger attempts = new AtomicInteger();
        TestConsumer consumer = TestConsumer.create(processedEvents, dlqForwarder, rec -> {
            attempts.incrementAndGet();
            throw new RuntimeException("processing boom");
        });

        consumer.consume(recordWithEventId(eventId));

        // Exactly 3 attempts (Requirement 22.6), then dead-letter.
        assertThat(attempts.get()).isEqualTo(IdempotentKafkaConsumer.MAX_RETRIES);
        assertThat(consumer.sleepCount.get()).isEqualTo(IdempotentKafkaConsumer.MAX_RETRIES - 1);
        assertThat(consumer.lastSleepMillis.get()).isEqualTo(IdempotentKafkaConsumer.RETRY_DELAY_MS);
        verify(dlqForwarder, times(1)).forward(eq(TOPIC), eq("booking-1"), eq(eventId.toString()),
                anyString(), anyString());
        // A failed event is never marked processed, so a later retry could still succeed.
        assertThat(processedEvents.existsByConsumerGroupAndEventId(GROUP, eventId)).isFalse();
    }

    @Test
    void succeedsOnSecondAttemptWithoutDlq() {
        UUID eventId = UUID.randomUUID();
        AtomicInteger attempts = new AtomicInteger();
        TestConsumer consumer = TestConsumer.create(processedEvents, dlqForwarder, rec -> {
            if (attempts.incrementAndGet() == 1) {
                throw new RuntimeException("transient");
            }
        });

        consumer.consume(recordWithEventId(eventId));

        assertThat(attempts.get()).isEqualTo(2);
        verify(dlqForwarder, never()).forward(anyString(), anyString(), anyString(), anyString(), anyString());
        assertThat(processedEvents.existsByConsumerGroupAndEventId(GROUP, eventId)).isTrue();
    }

    @Test
    void recordWithoutEventIdHeaderIsDeadLettered() {
        TestConsumer consumer = TestConsumer.create(processedEvents, dlqForwarder, rec -> { });
        ConsumerRecord<String, String> record =
                new ConsumerRecord<>(TOPIC, 0, 0L, "booking-1", "{}");

        consumer.consume(record);

        verify(dlqForwarder, times(1)).forward(eq(TOPIC), eq("booking-1"), eq(null), anyString(), anyString());
    }

    /** Test consumer with a supplied handler and a fake, fast sleeper that records its calls. */
    private static final class TestConsumer extends IdempotentKafkaConsumer {
        private final java.util.function.Consumer<ConsumerRecord<String, String>> handler;
        final AtomicInteger sleepCount;
        final java.util.concurrent.atomic.AtomicLong lastSleepMillis;

        private TestConsumer(ProcessedEventRepository repo,
                             DlqForwarder dlq,
                             java.util.function.Consumer<ConsumerRecord<String, String>> handler,
                             CapturingSleeper sleeper) {
            // Inject a fast, non-blocking sleeper so retry tests do not incur the real 5 s delay.
            super(GROUP, repo, dlq, sleeper);
            this.handler = handler;
            this.sleepCount = sleeper.count;
            this.lastSleepMillis = sleeper.lastMillis;
        }

        static TestConsumer create(ProcessedEventRepository repo,
                                   DlqForwarder dlq,
                                   java.util.function.Consumer<ConsumerRecord<String, String>> handler) {
            return new TestConsumer(repo, dlq, handler, new CapturingSleeper());
        }

        @Override
        protected void handle(ConsumerRecord<String, String> record) {
            handler.accept(record);
        }
    }

    /** Records how many times and with what delay the consumer paused, without actually sleeping. */
    private static final class CapturingSleeper implements IdempotentKafkaConsumer.Sleeper {
        final AtomicInteger count = new AtomicInteger();
        final java.util.concurrent.atomic.AtomicLong lastMillis = new java.util.concurrent.atomic.AtomicLong();

        @Override
        public void sleep(long millis) {
            count.incrementAndGet();
            lastMillis.set(millis);
        }
    }

    /** In-memory stand-in for the JPA repository. */
    private static final class FakeProcessedEventRepository
            implements ProcessedEventRepository {
        private final Set<String> keys = new HashSet<>();

        @Override
        public boolean existsByConsumerGroupAndEventId(String consumerGroup, UUID eventId) {
            return keys.contains(consumerGroup + "|" + eventId);
        }

        @Override
        public <S extends ProcessedEventEntity> S save(S entity) {
            keys.add(entity.getConsumerGroup() + "|" + entity.getEventId());
            return entity;
        }

        // ---- unused JpaRepository methods ----
        @Override public java.util.List<ProcessedEventEntity> findAll() { throw new UnsupportedOperationException(); }
        @Override public java.util.List<ProcessedEventEntity> findAll(org.springframework.data.domain.Sort sort) { throw new UnsupportedOperationException(); }
        @Override public java.util.List<ProcessedEventEntity> findAllById(Iterable<ProcessedEventEntity.ProcessedEventId> ids) { throw new UnsupportedOperationException(); }
        @Override public <S extends ProcessedEventEntity> java.util.List<S> saveAll(Iterable<S> entities) { throw new UnsupportedOperationException(); }
        @Override public void flush() { }
        @Override public <S extends ProcessedEventEntity> S saveAndFlush(S entity) { return save(entity); }
        @Override public <S extends ProcessedEventEntity> java.util.List<S> saveAllAndFlush(Iterable<S> entities) { throw new UnsupportedOperationException(); }
        @Override public void deleteAllInBatch(Iterable<ProcessedEventEntity> entities) { }
        @Override public void deleteAllByIdInBatch(Iterable<ProcessedEventEntity.ProcessedEventId> ids) { }
        @Override public void deleteAllInBatch() { }
        @Override public ProcessedEventEntity getOne(ProcessedEventEntity.ProcessedEventId id) { throw new UnsupportedOperationException(); }
        @Override public ProcessedEventEntity getById(ProcessedEventEntity.ProcessedEventId id) { throw new UnsupportedOperationException(); }
        @Override public ProcessedEventEntity getReferenceById(ProcessedEventEntity.ProcessedEventId id) { throw new UnsupportedOperationException(); }
        @Override public <S extends ProcessedEventEntity> java.util.Optional<S> findOne(org.springframework.data.domain.Example<S> example) { throw new UnsupportedOperationException(); }
        @Override public <S extends ProcessedEventEntity> java.util.List<S> findAll(org.springframework.data.domain.Example<S> example) { throw new UnsupportedOperationException(); }
        @Override public <S extends ProcessedEventEntity> java.util.List<S> findAll(org.springframework.data.domain.Example<S> example, org.springframework.data.domain.Sort sort) { throw new UnsupportedOperationException(); }
        @Override public <S extends ProcessedEventEntity> org.springframework.data.domain.Page<S> findAll(org.springframework.data.domain.Example<S> example, org.springframework.data.domain.Pageable pageable) { throw new UnsupportedOperationException(); }
        @Override public <S extends ProcessedEventEntity> long count(org.springframework.data.domain.Example<S> example) { throw new UnsupportedOperationException(); }
        @Override public <S extends ProcessedEventEntity> boolean exists(org.springframework.data.domain.Example<S> example) { throw new UnsupportedOperationException(); }
        @Override public <S extends ProcessedEventEntity, R> R findBy(org.springframework.data.domain.Example<S> example, java.util.function.Function<org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery<S>, R> queryFunction) { throw new UnsupportedOperationException(); }
        @Override public java.util.Optional<ProcessedEventEntity> findById(ProcessedEventEntity.ProcessedEventId id) { throw new UnsupportedOperationException(); }
        @Override public boolean existsById(ProcessedEventEntity.ProcessedEventId id) { throw new UnsupportedOperationException(); }
        @Override public long count() { return keys.size(); }
        @Override public void deleteById(ProcessedEventEntity.ProcessedEventId id) { }
        @Override public void delete(ProcessedEventEntity entity) { }
        @Override public void deleteAllById(Iterable<? extends ProcessedEventEntity.ProcessedEventId> ids) { }
        @Override public void deleteAll(Iterable<? extends ProcessedEventEntity> entities) { }
        @Override public void deleteAll() { keys.clear(); }
        @Override public org.springframework.data.domain.Page<ProcessedEventEntity> findAll(org.springframework.data.domain.Pageable pageable) { throw new UnsupportedOperationException(); }
    }
}
