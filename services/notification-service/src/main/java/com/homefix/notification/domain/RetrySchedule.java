package com.homefix.notification.domain;

import java.time.Duration;

/**
 * Pure, side-effect-free model of the notification delivery retry policy (Requirement 17.8):
 * up to {@code maxAttempts} delivery attempts, and after a failed attempt the delivery waits an
 * exponentially increasing backoff before the next attempt.
 *
 * <p>With the defaults ({@code maxAttempts = 3}, {@code initialBackoff = 1 s}) the backoff before
 * retry 1 is 1 s, before retry 2 is 2 s, and before retry 3 is 4 s — i.e. the 1 s / 2 s / 4 s
 * schedule. After the final attempt fails the delivery is marked permanently failed.
 *
 * <p>Factored out as a dependency-free class so both the orchestrator and the future property
 * test (Property 22 family) can exercise the schedule directly.
 */
public final class RetrySchedule {

    private final int maxAttempts;
    private final Duration initialBackoff;

    public RetrySchedule(int maxAttempts, Duration initialBackoff) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        }
        if (initialBackoff == null || initialBackoff.isNegative()) {
            throw new IllegalArgumentException("initialBackoff must be non-null and non-negative");
        }
        this.maxAttempts = maxAttempts;
        this.initialBackoff = initialBackoff;
    }

    /** The maximum number of delivery attempts before permanent failure. */
    public int maxAttempts() {
        return maxAttempts;
    }

    /**
     * The backoff to wait before the retry that follows a failed attempt.
     *
     * @param failedAttempt the 1-based number of the attempt that just failed
     * @return the delay before the next attempt: {@code initialBackoff * 2^(failedAttempt-1)}
     * @throws IllegalArgumentException if {@code failedAttempt < 1}
     */
    public Duration backoffAfterAttempt(int failedAttempt) {
        if (failedAttempt < 1) {
            throw new IllegalArgumentException("failedAttempt must be >= 1");
        }
        long multiplier = 1L << (failedAttempt - 1);
        return initialBackoff.multipliedBy(multiplier);
    }

    /**
     * Whether another attempt should follow the attempt that just failed.
     *
     * @param failedAttempt the 1-based number of the attempt that just failed
     * @return {@code true} while more attempts remain, {@code false} once the limit is reached
     */
    public boolean shouldRetryAfterAttempt(int failedAttempt) {
        return failedAttempt < maxAttempts;
    }
}
