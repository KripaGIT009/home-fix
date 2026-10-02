package com.homefix.shared.outbox.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.KafkaHeaders;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link IdempotentKafkaConsumer}: duplicate-event skipping (Requirement 22.5)
 * and dead-letter forwarding after 3 failed attempts (Requirement 22.6), both when called directly
 * (attempts back to back) and when the listener container drives the attempts through the
 * delivery-attempt header (non-final failures rethrown, final failure dead-lettered).
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
    void forwardsToDlqAfterThreeFailedRetriesWithoutSleeping() {
        UUID eventId = UUID.randomUUID();
        AtomicInteger attempts = new AtomicInteger();
        TestConsumer consumer = TestConsumer.create(processedEvents, dlqForwarder, rec -> {
            attempts.incrementAndGet();
            throw new RuntimeException("processing boom");
        });

        long started = System.nanoTime();
        consumer.consume(recordWithEventId(eventId));
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;

        // Exactly 3 attempts (Requirement 22.6), then dead-letter.
        assertThat(attempts.get()).isEqualTo(IdempotentKafkaConsumer.MAX_RETRIES);
        verify(dlqForwarder, times(1)).forward(eq(TOPIC), eq("booking-1"), eq(eventId.toString()),
                anyString(), eq("java.lang.RuntimeException: processing boom"));
        // A failed event is never marked processed, so a later retry could still succeed.
        assertThat(processedEvents.existsByConsumerGroupAndEventId(GROUP, eventId)).isFalse();
        // The consumer itself never sleeps: the retry delay belongs to the listener container.
        assertThat(elapsedMillis).isLessThan(IdempotentKafkaConsumer.RETRY_DELAY_MS);
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

    // ----- container-managed attempts (delivery-attempt header present) -----

    @Test
    void containerManagedNonFinalFailureIsRethrownForRedelivery() {
        UUID eventId = UUID.randomUUID();
        AtomicInteger attempts = new AtomicInteger();
        RuntimeException boom = new RuntimeException("downstream unavailable");
        TestConsumer consumer = TestConsumer.create(processedEvents, dlqForwarder, rec -> {
            attempts.incrementAndGet();
            throw boom;
        });

        for (int attempt = 1; attempt < IdempotentKafkaConsumer.MAX_RETRIES; attempt++) {
            ConsumerRecord<String, String> delivery = withDeliveryAttempt(recordWithEventId(eventId), attempt);
            assertThatThrownBy(() -> consumer.consume(delivery)).isSameAs(boom);
        }

        // One handle() call per delivery: the container, not the consumer, drives the retries.
        assertThat(attempts.get()).isEqualTo(IdempotentKafkaConsumer.MAX_RETRIES - 1);
        verify(dlqForwarder, never()).forward(anyString(), anyString(), anyString(), anyString(), anyString());
        assertThat(processedEvents.existsByConsumerGroupAndEventId(GROUP, eventId)).isFalse();
    }

    @Test
    void containerManagedFinalFailureIsDeadLetteredNotRethrown() {
        UUID eventId = UUID.randomUUID();
        AtomicInteger attempts = new AtomicInteger();
        TestConsumer consumer = TestConsumer.create(processedEvents, dlqForwarder, rec -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("still broken");
        });

        consumer.consume(withDeliveryAttempt(recordWithEventId(eventId), IdempotentKafkaConsumer.MAX_RETRIES));

        assertThat(attempts.get()).isEqualTo(1);
        verify(dlqForwarder, times(1)).forward(eq(TOPIC), eq("booking-1"), eq(eventId.toString()),
                eq("{\"payload\":true}"), eq("java.lang.IllegalStateException: still broken"));
        assertThat(processedEvents.existsByConsumerGroupAndEventId(GROUP, eventId)).isFalse();
    }

    @Test
    void containerManagedRetrySucceedsAndRecordsTheEvent() {
        UUID eventId = UUID.randomUUID();
        AtomicInteger attempts = new AtomicInteger();
        TestConsumer consumer = TestConsumer.create(processedEvents, dlqForwarder,
                rec -> attempts.incrementAndGet());

        consumer.consume(withDeliveryAttempt(recordWithEventId(eventId), 2));
        // A later redelivery of the same event is a duplicate whatever its attempt number.
        consumer.consume(withDeliveryAttempt(recordWithEventId(eventId), 1));

        assertThat(attempts.get()).isEqualTo(1);
        assertThat(processedEvents.existsByConsumerGroupAndEventId(GROUP, eventId)).isTrue();
        verify(dlqForwarder, never()).forward(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void deliveryAttemptHeaderIsDecodedAsBigEndianInt() {
        assertThat(IdempotentKafkaConsumer.extractDeliveryAttempt(recordWithEventId(UUID.randomUUID()))).isNull();

        assertThat(IdempotentKafkaConsumer.extractDeliveryAttempt(
                withDeliveryAttempt(recordWithEventId(UUID.randomUUID()), 7))).isEqualTo(7);

        ConsumerRecord<String, String> malformed = recordWithEventId(UUID.randomUUID());
        malformed.headers().add(new RecordHeader(KafkaHeaders.DELIVERY_ATTEMPT, new byte[] {1, 2}));
        assertThat(IdempotentKafkaConsumer.extractDeliveryAttempt(malformed)).isNull();
    }

    private static ConsumerRecord<String, String> withDeliveryAttempt(ConsumerRecord<String, String> record,
                                                                      int attempt) {
        record.headers().add(new RecordHeader(KafkaHeaders.DELIVERY_ATTEMPT,
                ByteBuffer.allocate(Integer.BYTES).putInt(attempt).array()));
        return record;
    }

    /** Test consumer with a supplied handler. */
    private static final class TestConsumer extends IdempotentKafkaConsumer {
        private final java.util.function.Consumer<ConsumerRecord<String, String>> handler;

        private TestConsumer(ProcessedEventRepository repo,
                             DlqForwarder dlq,
                             java.util.function.Consumer<ConsumerRecord<String, String>> handler) {
            super(GROUP, repo, dlq);
            this.handler = handler;
        }

        static TestConsumer create(ProcessedEventRepository repo,
                                   DlqForwarder dlq,
                                   java.util.function.Consumer<ConsumerRecord<String, String>> handler) {
            return new TestConsumer(repo, dlq, handler);
        }

        @Override
        protected void handle(ConsumerRecord<String, String> record) {
            handler.accept(record);
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
