package com.homefix.shared.resilience;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.RetryConfig;
import org.junit.jupiter.api.Test;

/**
 * Verifies the shared factory produces breakers and retries matching the mandated platform
 * configuration (Requirements 24.1, 24.2).
 */
class ResilienceFactoryTest {

    @Test
    void circuitBreakerConfigMatchesRequirement241() {
        CircuitBreakerConfig config = ResilienceFactory.defaultCircuitBreakerConfig();

        assertThat(config.getFailureRateThreshold()).isEqualTo(50.0f);
        assertThat(config.getSlidingWindowType()).isEqualTo(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED);
        assertThat(config.getSlidingWindowSize()).isEqualTo(10);
        assertThat(config.getMinimumNumberOfCalls()).isEqualTo(10);
    }

    @Test
    void retryConfigMatchesRequirement242() {
        RetryConfig config = ResilienceFactory.defaultRetryConfig();

        assertThat(config.getMaxAttempts()).isEqualTo(3);
        // First retry waits the initial backoff of 500 ms.
        assertThat(config.getIntervalFunction().apply(1)).isEqualTo(500L);
        // Backoff grows exponentially (x2) but is capped at 8 s.
        assertThat(config.getIntervalFunction().apply(2)).isEqualTo(1_000L);
        assertThat(config.getIntervalFunction().apply(10)).isEqualTo(8_000L);
    }

    @Test
    void breakerOpensAfterFiftyPercentFailuresInTenCallWindow() {
        ResilienceFactory factory = new ResilienceFactory();
        CircuitBreaker breaker = factory.circuitBreaker("pricing-engine");

        // 5 successes + 5 transient failures over the 10-call window = exactly 50% failure rate.
        for (int i = 0; i < 5; i++) {
            breaker.onSuccess(1, java.util.concurrent.TimeUnit.MILLISECONDS);
            breaker.onError(1, java.util.concurrent.TimeUnit.MILLISECONDS,
                    new TransientFailures.ServerErrorException(503, "boom"));
        }

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    void breakerStaysClosedBelowThreshold() {
        ResilienceFactory factory = new ResilienceFactory();
        CircuitBreaker breaker = factory.circuitBreaker("notification-service");

        // 6 successes + 4 failures = 40% failure rate, below the 50% threshold.
        for (int i = 0; i < 6; i++) {
            breaker.onSuccess(1, java.util.concurrent.TimeUnit.MILLISECONDS);
        }
        for (int i = 0; i < 4; i++) {
            breaker.onError(1, java.util.concurrent.TimeUnit.MILLISECONDS,
                    new TransientFailures.ServerErrorException(500, "boom"));
        }

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void timeoutProfilesMatchRequirement243() {
        assertThat(TimeoutProfile.CRITICAL_PATH.timeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(TimeoutProfile.STANDARD.timeout()).isEqualTo(Duration.ofSeconds(15));
    }
}
