package com.homefix.shared.outbox.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link IdempotentConsumerErrorHandler}'s safety-net recoverer: a record whose
 * failure escaped {@link IdempotentKafkaConsumer#consume} is dead-lettered through the shared
 * {@link DlqForwarder} with the same naming and headers, never silently dropped.
 */
class IdempotentConsumerErrorHandlerTest {

    @Test
    void recovererForwardsToDlqWithTheUnwrappedCause() {
        DlqForwarder forwarder = mock(DlqForwarder.class);
        ConsumerRecordRecoverer recoverer = IdempotentConsumerErrorHandler.recoverer(() -> forwarder);
        UUID eventId = UUID.randomUUID();
        ConsumerRecord<String, String> record = new ConsumerRecord<>("JobStarted", 0, 4L, "key-1", "{}");
        record.headers().add(new RecordHeader(KafkaProducerTemplate.HEADER_EVENT_ID,
                eventId.toString().getBytes(StandardCharsets.UTF_8)));

        recoverer.accept(record, new ListenerExecutionFailedException("listener failed",
                new IllegalStateException("database unavailable")));

        verify(forwarder).forward("JobStarted", "key-1", eventId.toString(), "{}",
                "java.lang.IllegalStateException: database unavailable");
    }

    @Test
    void recovererToleratesMissingHeaderAndNullKey() {
        DlqForwarder forwarder = mock(DlqForwarder.class);
        ConsumerRecordRecoverer recoverer = IdempotentConsumerErrorHandler.recoverer(() -> forwarder);
        ConsumerRecord<String, String> record = new ConsumerRecord<>("JobStarted", 0, 4L, null, "{}");

        recoverer.accept(record, new RuntimeException("boom"));

        verify(forwarder).forward("JobStarted", null, null, "{}", "java.lang.RuntimeException: boom");
    }

    @Test
    void recovererWithoutForwarderLogsInsteadOfThrowing() {
        ConsumerRecordRecoverer recoverer = IdempotentConsumerErrorHandler.recoverer(() -> null);
        ConsumerRecord<String, String> record = new ConsumerRecord<>("JobStarted", 0, 4L, "k", "{}");

        assertThatCode(() -> recoverer.accept(record, new RuntimeException("boom"))).doesNotThrowAnyException();
    }

    @Test
    void destroyStopsTheResumeScheduler() {
        IdempotentConsumerErrorHandler handler =
                IdempotentConsumerErrorHandler.create(() -> null, Duration.ofMillis(100));
        ThreadPoolTaskScheduler scheduler =
                (ThreadPoolTaskScheduler) ReflectionTestUtils.getField(handler, "resumeScheduler");

        handler.destroy();

        assertThat(scheduler.getScheduledExecutor().isShutdown()).isTrue();
    }
}
