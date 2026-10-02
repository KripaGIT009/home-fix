package com.homefix.shared.outbox.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.ContainerPausingBackOffHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.ListenerContainerPauseService;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.util.backoff.FixedBackOff;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Supplier;

/**
 * Listener-container error handler that supplies the retry delay for {@link IdempotentKafkaConsumer}
 * without blocking the consumer thread (Requirement 22.6).
 *
 * <p>A failed delivery is re-seeked and redelivered up to {@link IdempotentKafkaConsumer#MAX_RETRIES}
 * attempts in total, {@code retryDelay} apart. The delay is a {@link ContainerPausingBackOffHandler}:
 * the listener container is <em>paused</em> for the delay and resumed by a scheduler, so the
 * consumer keeps polling (no {@code max.poll.interval.ms} eviction, no rebalance storm) and stays
 * responsive to shutdown, where the consumer used to {@code Thread.sleep} on the listener thread.
 *
 * <p>Dead-lettering normally happens inside {@link IdempotentKafkaConsumer#consume} on the final
 * attempt, so the record is acknowledged without ever reaching this handler's recoverer. The
 * recoverer is the safety net for an exception that escapes {@code consume} itself — for example
 * the dedup lookup failing because the database is down — and forwards through the same
 * {@link DlqForwarder} ({@code <topic>.DLT}, same headers) instead of the framework default, which
 * would log the record and drop it. The forwarder throws when the dead-letter write is not
 * acknowledged; the recoverer lets that propagate, so this handler treats the recovery as failed,
 * re-seeks the record and tries again rather than committing its offset.
 *
 * <p>Owns a one-thread scheduler for the resume timers; it is deliberately not a
 * {@code TaskScheduler} bean, which would make Spring Boot's own scheduler auto-configuration back
 * off in every service that depends on this module.
 */
public class IdempotentConsumerErrorHandler extends DefaultErrorHandler implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(IdempotentConsumerErrorHandler.class);

    private final ThreadPoolTaskScheduler resumeScheduler;

    private IdempotentConsumerErrorHandler(ConsumerRecordRecoverer recoverer, FixedBackOff backOff,
                                           ThreadPoolTaskScheduler resumeScheduler) {
        super(recoverer, backOff,
                new ContainerPausingBackOffHandler(new ListenerContainerPauseService(null, resumeScheduler)));
        this.resumeScheduler = resumeScheduler;
        // DefaultErrorHandler treats ClassCastException as not retryable and recovers on the first
        // failure. Business code in handle() can throw it, and IdempotentKafkaConsumer has always
        // given every handle() failure the full set of attempts, so keep that contract. The other
        // default non-retryable types are framework failures (deserialization, conversion) that
        // never reach handle() and would fail identically on every attempt.
        removeClassification(ClassCastException.class);
        // Each non-final attempt makes the container log the "seek to current" exception at this
        // level, stack trace included. The consumer already logs a WARN per failed attempt and an
        // ERROR when it dead-letters, so a retry in progress is not an error.
        setLogLevel(KafkaException.Level.WARN);
    }

    /**
     * @param dlqForwarder supplies the forwarder used by the recoverer, resolved lazily; may
     *                     return {@code null}, in which case an exhausted record is logged and
     *                     skipped
     * @param retryDelay   pause between attempts
     */
    public static IdempotentConsumerErrorHandler create(Supplier<DlqForwarder> dlqForwarder,
                                                        Duration retryDelay) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("kafka-retry-resume-");
        scheduler.setDaemon(true);
        scheduler.initialize();
        FixedBackOff backOff = new FixedBackOff(retryDelay.toMillis(), IdempotentKafkaConsumer.MAX_RETRIES - 1L);
        return new IdempotentConsumerErrorHandler(recoverer(dlqForwarder), backOff, scheduler);
    }

    static ConsumerRecordRecoverer recoverer(Supplier<DlqForwarder> dlqForwarder) {
        return (record, exception) -> {
            Throwable cause = exception instanceof ListenerExecutionFailedException && exception.getCause() != null
                    ? exception.getCause()
                    : exception;
            String reason = cause == null ? "unknown" : cause.toString();
            DlqForwarder forwarder = dlqForwarder.get();
            if (forwarder == null) {
                log.error("Record on topic {} partition {} offset {} failed {} attempts and no DlqForwarder"
                                + " is configured; skipping it. Last failure: {}",
                        record.topic(), record.partition(), record.offset(),
                        IdempotentKafkaConsumer.MAX_RETRIES, reason);
                return;
            }
            forwarder.forward(record.topic(), asString(record.key()), eventIdOf(record),
                    asString(record.value()), reason);
        };
    }

    private static String eventIdOf(ConsumerRecord<?, ?> record) {
        Header header = record.headers().lastHeader(KafkaProducerTemplate.HEADER_EVENT_ID);
        return header == null || header.value() == null
                ? null
                : new String(header.value(), StandardCharsets.UTF_8);
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    @Override
    public void destroy() {
        resumeScheduler.shutdown();
    }
}
