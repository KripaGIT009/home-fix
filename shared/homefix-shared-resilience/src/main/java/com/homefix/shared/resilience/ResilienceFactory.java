package com.homefix.shared.resilience;

import java.time.Duration;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;

/**
 * Builds the shared Resilience4j primitives with the platform-wide configuration mandated by
 * Requirement 24. A single factory instance is shared per service so every dependency gets a
 * consistently configured, independently-tracked circuit breaker and retry.
 *
 * <p>Configuration (identical across all services, per Requirements 24.1 and 24.2):
 * <ul>
 *   <li><b>Circuit breaker</b> — 50% failure-rate threshold over a count-based sliding window of
 *       10 calls; 30 seconds in the open state before transitioning to half-open.</li>
 *   <li><b>Retry</b> — maximum 3 attempts, exponential backoff starting at 500 ms capped at 8 s,
 *       only for transient failures ({@link TransientFailures#isTransient}).</li>
 * </ul>
 */
public class ResilienceFactory {

    /** Failure-rate threshold, in percent, at which the breaker opens (Requirement 24.1). */
    public static final float FAILURE_RATE_THRESHOLD = 50.0f;

    /** Number of calls in the count-based sliding window (Requirement 24.1). */
    public static final int SLIDING_WINDOW_SIZE = 10;

    /** How long the breaker stays open before probing half-open (Requirement 24.1). */
    public static final Duration OPEN_STATE_WAIT = Duration.ofSeconds(30);

    /** Total attempts including the first call: 1 initial + up to 2 retries = 3 (Requirement 24.2). */
    public static final int MAX_ATTEMPTS = 3;

    /** Initial backoff before the first retry (Requirement 24.2). */
    public static final Duration INITIAL_BACKOFF = Duration.ofMillis(500);

    /** Backoff ceiling — no wait between attempts exceeds this (Requirement 24.2). */
    public static final Duration MAX_BACKOFF = Duration.ofSeconds(8);

    private static final double BACKOFF_MULTIPLIER = 2.0;

    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final RetryRegistry retryRegistry;

    public ResilienceFactory() {
        this.circuitBreakerRegistry = CircuitBreakerRegistry.of(defaultCircuitBreakerConfig());
        this.retryRegistry = RetryRegistry.of(defaultRetryConfig());
    }

    /**
     * Returns the circuit breaker for the named dependency, creating it on first use. Names should
     * identify the downstream by service (e.g. {@code "pricing-engine"}) so metrics and the
     * fallback WARN log (Requirement 24.4) can attribute the failure.
     */
    public CircuitBreaker circuitBreaker(String dependencyName) {
        return circuitBreakerRegistry.circuitBreaker(dependencyName);
    }

    /** Returns the retry policy for the named dependency, creating it on first use. */
    public Retry retry(String dependencyName) {
        return retryRegistry.retry(dependencyName);
    }

    /** Exposed so services can register the Micrometer breaker-state gauges (Requirement 25.4). */
    public CircuitBreakerRegistry circuitBreakerRegistry() {
        return circuitBreakerRegistry;
    }

    public RetryRegistry retryRegistry() {
        return retryRegistry;
    }

    static CircuitBreakerConfig defaultCircuitBreakerConfig() {
        return CircuitBreakerConfig.custom()
                .failureRateThreshold(FAILURE_RATE_THRESHOLD)
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(SLIDING_WINDOW_SIZE)
                // Require the full window before computing the failure rate so the breaker opens
                // deterministically at "50% of 10 calls" rather than on the very first failures.
                .minimumNumberOfCalls(SLIDING_WINDOW_SIZE)
                .waitDurationInOpenState(OPEN_STATE_WAIT)
                .permittedNumberOfCallsInHalfOpenState(SLIDING_WINDOW_SIZE / 2)
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                // Only transient failures should trip the breaker; a 4xx is a caller error.
                .recordException(TransientFailures::isTransient)
                .build();
    }

    static RetryConfig defaultRetryConfig() {
        IntervalFunction backoff = IntervalFunction.ofExponentialBackoff(
                INITIAL_BACKOFF, BACKOFF_MULTIPLIER, MAX_BACKOFF);
        return RetryConfig.custom()
                .maxAttempts(MAX_ATTEMPTS)
                .intervalFunction(backoff)
                .retryOnException(TransientFailures::isTransient)
                .build();
    }
}
