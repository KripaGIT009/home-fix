package com.homefix.outbox.backoff;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link ExponentialBackoff}: the retry schedule must be exactly
 * 1 s, 2 s, 4 s, 8 s, 16 s, 32 s, then saturate at the 60 s cap (Requirement 22.4).
 */
class ExponentialBackoffTest {

    private final ExponentialBackoff backoff =
            new ExponentialBackoff(Duration.ofSeconds(1), Duration.ofSeconds(60));

    @Test
    void scheduleDoublesFromOneSecondUntilItSaturatesTheSixtySecondCap() {
        assertThat(backoff.delayForAttempt(1)).isEqualTo(Duration.ofSeconds(1));
        assertThat(backoff.delayForAttempt(2)).isEqualTo(Duration.ofSeconds(2));
        assertThat(backoff.delayForAttempt(3)).isEqualTo(Duration.ofSeconds(4));
        assertThat(backoff.delayForAttempt(4)).isEqualTo(Duration.ofSeconds(8));
        assertThat(backoff.delayForAttempt(5)).isEqualTo(Duration.ofSeconds(16));
        assertThat(backoff.delayForAttempt(6)).isEqualTo(Duration.ofSeconds(32));
        // 2^6 = 64 s would exceed the cap, so attempt 7 onward stays at 60 s.
        assertThat(backoff.delayForAttempt(7)).isEqualTo(Duration.ofSeconds(60));
        assertThat(backoff.delayForAttempt(8)).isEqualTo(Duration.ofSeconds(60));
        assertThat(backoff.delayForAttempt(9)).isEqualTo(Duration.ofSeconds(60));
        assertThat(backoff.delayForAttempt(10)).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void veryLargeAttemptNumberDoesNotOverflowAndStaysCapped() {
        assertThat(backoff.delayForAttempt(1000)).isEqualTo(Duration.ofSeconds(60));
        assertThat(backoff.delayForAttempt(Integer.MAX_VALUE)).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void rejectsNonPositiveInitial() {
        assertThatThrownBy(() -> new ExponentialBackoff(Duration.ZERO, Duration.ofSeconds(60)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExponentialBackoff(Duration.ofSeconds(-1), Duration.ofSeconds(60)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMaxIntervalBelowInitial() {
        assertThatThrownBy(() -> new ExponentialBackoff(Duration.ofSeconds(10), Duration.ofSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAttemptBelowOne() {
        assertThatThrownBy(() -> backoff.delayForAttempt(0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
