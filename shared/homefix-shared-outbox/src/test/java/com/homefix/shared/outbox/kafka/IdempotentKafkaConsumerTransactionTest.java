package com.homefix.shared.outbox.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link IdempotentKafkaConsumer} with a real transaction manager and the real
 * {@code processed_event} table (H2): the dedup insert and {@code handle()}'s database work commit
 * or roll back together, and a duplicate that slipped past the existence check is stopped by the
 * primary key before {@code handle()} runs.
 *
 * <p>The ambient {@code @DataJpaTest} transaction is disabled, as in
 * {@code OutboxWriteAtomicityTest}: the consumer's own transaction is what is under test.
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.properties.hibernate.hbm2ddl.create_namespaces=true")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class IdempotentKafkaConsumerTransactionTest {

    private static final String GROUP = "invoice-service.payment-completed";
    /** Rows under this group stand in for a handler's business writes. */
    private static final String SIDE_EFFECT_GROUP = "side-effect";

    @Autowired
    private ProcessedEventRepository processedEvents;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private DlqForwarder dlqForwarder;

    @BeforeEach
    void setUp() {
        processedEvents.deleteAll();
        dlqForwarder = mock(DlqForwarder.class);
    }

    @Test
    void handleRunsInsideTheTransactionAndCommitsWithTheDedupRow() {
        UUID eventId = UUID.randomUUID();
        AtomicBoolean inTransaction = new AtomicBoolean();
        TestConsumer consumer = consumer(processedEvents, rec -> {
            inTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
            writeSideEffect(eventId);
        });

        consumer.consume(record(eventId));

        assertThat(inTransaction).isTrue();
        assertThat(processedEvents.existsByConsumerGroupAndEventId(GROUP, eventId)).isTrue();
        assertThat(processedEvents.existsByConsumerGroupAndEventId(SIDE_EFFECT_GROUP, eventId)).isTrue();
    }

    @Test
    void failedAttemptRollsBackBothItsWritesAndTheDedupRowSoTheRetryAppliesThemOnce() {
        UUID eventId = UUID.randomUUID();
        AtomicInteger attempts = new AtomicInteger();
        TestConsumer consumer = consumer(processedEvents, rec -> {
            writeSideEffect(eventId);
            if (attempts.incrementAndGet() == 1) {
                // Fails after its business write, like a dropped connection after the invoice row.
                throw new IllegalStateException("connection reset");
            }
        });

        consumer.consume(record(eventId));

        assertThat(attempts).hasValue(2);
        // The first attempt's write was rolled back with its dedup row; only the retry's survives
        // (a second committed side-effect row would have violated the primary key and failed).
        assertThat(processedEvents.existsByConsumerGroupAndEventId(SIDE_EFFECT_GROUP, eventId)).isTrue();
        assertThat(processedEvents.existsByConsumerGroupAndEventId(GROUP, eventId)).isTrue();
        assertThat(processedEvents.count()).isEqualTo(2);
        verify(dlqForwarder, never()).forward(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void exhaustedEventLeavesNoDedupRowAndNoBusinessWrites() {
        UUID eventId = UUID.randomUUID();
        TestConsumer consumer = consumer(processedEvents, rec -> {
            writeSideEffect(eventId);
            throw new IllegalStateException("poison");
        });

        consumer.consume(record(eventId));

        assertThat(processedEvents.count()).isZero();
        verify(dlqForwarder).forward(any(), any(), any(), any(), any());
    }

    @Test
    void duplicatePastTheExistenceCheckIsStoppedByTheDedupInsertWithoutRunningHandle() {
        UUID eventId = UUID.randomUUID();
        // Another delivery already committed the event; this one's existence check raced it.
        processedEvents.save(new ProcessedEventEntity(GROUP, eventId));
        AtomicInteger handled = new AtomicInteger();
        TestConsumer consumer = consumer(existenceCheckAlwaysMisses(processedEvents),
                rec -> handled.incrementAndGet());

        consumer.consume(record(eventId));

        assertThat(handled).hasValue(0);
        verify(dlqForwarder, never()).forward(any(), any(), any(), any(), any());
    }

    @Test
    void integrityViolationRaisedByHandleItselfIsAFailureNotADuplicate() {
        UUID eventId = UUID.randomUUID();
        AtomicInteger attempts = new AtomicInteger();
        TestConsumer consumer = consumer(processedEvents, rec -> {
            attempts.incrementAndGet();
            throw new DataIntegrityViolationException("business constraint");
        });

        consumer.consume(record(eventId));

        assertThat(attempts).hasValue(IdempotentKafkaConsumer.MAX_RETRIES);
        verify(dlqForwarder).forward(any(), any(), any(), any(), any());
        assertThat(processedEvents.existsByConsumerGroupAndEventId(GROUP, eventId)).isFalse();
    }

    @Test
    void concurrentDeliveriesOfOneEventRunHandleOnce() throws Exception {
        UUID eventId = UUID.randomUUID();
        AtomicInteger handled = new AtomicInteger();
        CountDownLatch firstInsideHandle = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        TestConsumer consumer = consumer(existenceCheckAlwaysMisses(processedEvents), rec -> {
            handled.incrementAndGet();
            firstInsideHandle.countDown();
            await(releaseFirst);
        });

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = pool.submit(() -> consumer.consume(record(eventId)));
            assertThat(firstInsideHandle.await(10, TimeUnit.SECONDS)).isTrue();
            // The first delivery holds its uncommitted dedup row; the second one's insert queues
            // behind it.
            Future<?> second = pool.submit(() -> consumer.consume(record(eventId)));
            Thread.sleep(300);
            releaseFirst.countDown();
            first.get(20, TimeUnit.SECONDS);
            second.get(20, TimeUnit.SECONDS);
        } finally {
            releaseFirst.countDown();
            pool.shutdownNow();
        }

        assertThat(handled).hasValue(1);
        verify(dlqForwarder, never()).forward(any(), any(), any(), any(), any());
        assertThat(processedEvents.existsByConsumerGroupAndEventId(GROUP, eventId)).isTrue();
    }

    @Test
    void processedEventIsAlwaysInsertedSoARepeatedSaveFailsOnTheKey() {
        UUID eventId = UUID.randomUUID();
        processedEvents.save(new ProcessedEventEntity(GROUP, eventId));

        // Without Persistable this would be a merge: a silent no-op UPDATE of the existing row.
        assertThatThrownBy(() -> processedEvents.save(new ProcessedEventEntity(GROUP, eventId)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(processedEvents.findById(new ProcessedEventEntity.ProcessedEventId(GROUP, eventId)))
                .get().extracting(ProcessedEventEntity::isNew).isEqualTo(false);
    }

    // ---------------------------------------------------------------------

    private TestConsumer consumer(ProcessedEventRepository repository,
                                  Consumer<ConsumerRecord<String, String>> handler) {
        TestConsumer consumer = new TestConsumer(repository, dlqForwarder, handler);
        consumer.setTransactionManager(transactionManager);
        return consumer;
    }

    private void writeSideEffect(UUID eventId) {
        processedEvents.save(new ProcessedEventEntity(SIDE_EFFECT_GROUP, eventId));
    }

    /** The real repository, except that the pre-transaction existence check never finds a row. */
    private static ProcessedEventRepository existenceCheckAlwaysMisses(ProcessedEventRepository real) {
        return (ProcessedEventRepository) Proxy.newProxyInstance(
                ProcessedEventRepository.class.getClassLoader(),
                new Class<?>[] {ProcessedEventRepository.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("existsByConsumerGroupAndEventId")) {
                        return false;
                    }
                    try {
                        return method.invoke(real, args);
                    } catch (InvocationTargetException ex) {
                        throw ex.getCause();
                    }
                });
    }

    private static ConsumerRecord<String, String> record(UUID eventId) {
        ConsumerRecord<String, String> record = new ConsumerRecord<>("PaymentCompleted", 0, 0L, "k", "{}");
        record.headers().add(new RecordHeader(KafkaProducerTemplate.HEADER_EVENT_ID,
                eventId.toString().getBytes(StandardCharsets.UTF_8)));
        return record;
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class TestConsumer extends IdempotentKafkaConsumer {
        private final Consumer<ConsumerRecord<String, String>> handler;

        TestConsumer(ProcessedEventRepository repository, DlqForwarder dlqForwarder,
                     Consumer<ConsumerRecord<String, String>> handler) {
            super(GROUP, repository, dlqForwarder);
            this.handler = handler;
        }

        @Override
        protected void handle(ConsumerRecord<String, String> record) {
            handler.accept(record);
        }
    }
}
