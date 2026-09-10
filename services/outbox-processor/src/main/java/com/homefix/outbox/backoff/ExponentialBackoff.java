package com.homefix.outbox.backoff;

import java.time.Duration;

/**
 * Pure, side-effect-free exponential backoff schedule for outbox publish retries
 * (Requirement 22.4).
 *
 * <p>The delay before retry attempt {@code n} (1-based) is {@code initial × 2^(n-1)}, capped at
 * {@code maxInterval}. With the platform defaults ({@code initial = 1 s}, {@code maxInterval =
 * 60 s}) this yields the schedule 1 s, 2 s, 4 s, 8 s, 16 s, 32 s, 60 s, 60 s, … — i.e. it doubles
 * until it saturates the 60 s ceiling and then stays there.
 *
 * <p>Kept deliberately free of clocks, threads, and Spring so the schedule can be asserted
 * exactly in a unit test and reused wherever a delay must be computed.
 */
public final class ExponentialBackoff {

    private final Duration initial;
    private final Duration maxInterval;

    /**
     * @param initial     delay before the first retry (attempt 1); must be positive
     * @param maxInterval ceiling the delay may never exceed; must be &ge; {@code initial}
     */
    public ExponentialBackoff(Duration initial, Duration maxInterval) {
        if (initial == null || initial.isNegative() || initial.isZero()) {
            throw new IllegalArgumentException("initial backoff must be positive, got " + initial);
        }
        if (maxInterval == null || maxInterval.compareTo(initial) < 0) {
            throw new IllegalArgumentException(
                    "maxInterval must be >= initial (" + initial + "), got " + maxInterval);
        }
        this.initial = initial;
        this.maxInterval = maxInterval;
    }

    /**
     * Delay before the given 1-based retry attempt.
     *
     * @param attempt the retry attempt number, starting at 1
     * @return {@code min(initial × 2^(attempt-1), maxInterval)}
     * @throws IllegalArgumentException if {@code attempt < 1}
     */
    public Duration delayForAttempt(int attempt) {
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be >= 1, got " + attempt);
        }
        long initialMillis = initial.toMillis();
        long capMillis = maxInterval.toMillis();

        // Guard against overflow: once the shift would exceed the cap there is no point computing it.
        int shift = attempt - 1;
        if (shift >= 62 || initialMillis > (capMillis >> Math.min(shift, 62))) {
            return maxInterval;
        }
        long scaled = initialMillis << shift;
        return scaled >= capMillis ? maxInterval : Duration.ofMillis(scaled);
    }

    public Duration getInitial() {
        return initial;
    }

    public Duration getMaxInterval() {
        return maxInterval;
    }
}
