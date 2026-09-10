package com.homefix.notification.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;

/**
 * Verifies the retry schedule reproduces the mandated 1 s / 2 s / 4 s exponential backoff and
 * the 3-attempt limit (Requirement 17.8).
 */
class RetryScheduleTest {

    private final RetrySchedule schedule = new RetrySchedule(3, Duration.ofSeconds(1));

    @Test
    void backoffFollowsOneTwoFourSeconds() {
        assertThat(schedule.backoffAfterAttempt(1)).isEqualTo(Duration.ofSeconds(1));
        assertThat(schedule.backoffAfterAttempt(2)).isEqualTo(Duration.ofSeconds(2));
        assertThat(schedule.backoffAfterAttempt(3)).isEqualTo(Duration.ofSeconds(4));
    }

    @Test
    void retriesUntilTheAttemptLimitIsReached() {
        assertThat(schedule.maxAttempts()).isEqualTo(3);
        assertThat(schedule.shouldRetryAfterAttempt(1)).isTrue();
        assertThat(schedule.shouldRetryAfterAttempt(2)).isTrue();
        assertThat(schedule.shouldRetryAfterAttempt(3)).isFalse();
    }

    @Test
    void rejectsInvalidConfiguration() {
        assertThatThrownBy(() -> new RetrySchedule(0, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetrySchedule(3, Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> schedule.backoffAfterAttempt(0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
