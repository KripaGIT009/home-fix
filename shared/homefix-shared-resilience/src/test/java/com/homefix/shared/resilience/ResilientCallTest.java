package com.homefix.shared.resilience;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Behavioural tests for {@link ResilientCall}: transient-failure retry counting, circuit-breaker
 * open → fallback, and the WARN-level log naming the failed dependency (Requirements 24.2, 24.4).
 */
class ResilientCallTest {

    private ListAppender<ILoggingEvent> appender;
    private Logger callLogger;

    @BeforeEach
    void attachAppender() {
        callLogger = (Logger) LoggerFactory.getLogger(ResilientCall.class);
        appender = new ListAppender<>();
        appender.start();
        callLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        callLogger.detachAppender(appender);
    }

    @Test
    void returnsValueOnSuccessWithoutFallback() {
        ResilientCall<String> call = ResilientCall.forDependency(
                new ResilienceFactory(), "pricing-engine", TimeoutProfile.CRITICAL_PATH,
                cause -> "DEGRADED");

        String result = call.execute(() -> "OK");

        assertThat(result).isEqualTo("OK");
        assertThat(warnMessages()).isEmpty();
    }

    @Test
    void retriesTransientFailuresUpToThreeAttemptsThenFallsBack() {
        AtomicInteger attempts = new AtomicInteger();
        ResilientCall<String> call = ResilientCall.forDependency(
                new ResilienceFactory(), "pricing-engine", TimeoutProfile.STANDARD,
                cause -> "DEGRADED");

        String result = call.execute(() -> {
            attempts.incrementAndGet();
            throw new TransientFailures.ServerErrorException(503, "downstream 5xx");
        });

        // Requirement 24.2: 1 initial attempt + 2 retries = 3 total.
        assertThat(attempts.get()).isEqualTo(3);
        assertThat(result).isEqualTo("DEGRADED");
        assertThat(warnMessages()).anyMatch(m -> m.contains("pricing-engine"));
    }

    @Test
    void doesNotRetryNonTransientFailure() {
        AtomicInteger attempts = new AtomicInteger();
        ResilientCall<String> call = ResilientCall.forDependency(
                new ResilienceFactory(), "provider-service", TimeoutProfile.CRITICAL_PATH,
                cause -> "DEGRADED");

        String result = call.execute(() -> {
            attempts.incrementAndGet();
            throw new IllegalArgumentException("bad request (4xx-like)");
        });

        // A non-transient failure is not retried: exactly one attempt, then fallback.
        assertThat(attempts.get()).isEqualTo(1);
        assertThat(result).isEqualTo("DEGRADED");
    }

    @Test
    void openBreakerReturnsFallbackAndLogsWarnWithDependencyName() {
        ResilienceFactory factory = new ResilienceFactory();
        // Force the breaker for this dependency into the OPEN state.
        CircuitBreaker breaker = factory.circuitBreaker("dispatch-engine");
        breaker.transitionToOpenState();

        ResilientCall<String> call = ResilientCall.forDependency(
                factory, "dispatch-engine", TimeoutProfile.CRITICAL_PATH, cause -> "DEGRADED");

        AtomicInteger attempts = new AtomicInteger();
        String result = call.execute(() -> {
            attempts.incrementAndGet();
            return "OK";
        });

        // Breaker open: the guarded action is never invoked; fallback is returned.
        assertThat(attempts.get()).isZero();
        assertThat(result).isEqualTo("DEGRADED");

        assertThat(appender.list).anyMatch(e ->
                e.getLevel() == Level.WARN
                        && e.getFormattedMessage().contains("Circuit breaker OPEN")
                        && e.getFormattedMessage().contains("dispatch-engine"));
    }

    @Test
    void timeoutIsTreatedAsTransientAndFallsBack() {
        ResilientCall<String> call = ResilientCall.forDependency(
                new ResilienceFactory(), "slow-dependency", java.time.Duration.ofMillis(50),
                cause -> "DEGRADED");

        String result = call.execute(() -> {
            Thread.sleep(200);
            return "OK";
        });

        assertThat(result).isEqualTo("DEGRADED");
        assertThat(warnMessages()).anyMatch(m -> m.contains("slow-dependency"));
    }

    private java.util.List<String> warnMessages() {
        return appender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }
}
