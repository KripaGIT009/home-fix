package com.homefix.payment.service;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * Small exponential-backoff retry helper (Requirement 12.6, 12.11). Runs an action up to
 * {@code maxAttempts} times, doubling the backoff between attempts. Kept dependency-free and
 * synchronous so the payment flows stay easy to reason about and unit-test (tests set the base
 * backoff to zero to avoid real delays).
 */
public final class Retries {

    private Retries() {
    }

    /**
     * @return a {@link Result} describing whether the action ultimately succeeded, how many
     *         attempts were made, and the last error if it failed.
     */
    public static Result run(int maxAttempts, Duration baseBackoff, Runnable action) {
        return run(maxAttempts, baseBackoff, () -> {
            action.run();
            return null;
        });
    }

    public static <T> Result run(int maxAttempts, Duration baseBackoff, Supplier<T> action) {
        RuntimeException last = null;
        long backoffMillis = Math.max(0, baseBackoff.toMillis());
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                action.get();
                return new Result(true, attempt, null);
            } catch (RuntimeException e) {
                last = e;
                if (attempt < maxAttempts && backoffMillis > 0) {
                    sleep(backoffMillis);
                    backoffMillis *= 2;
                }
            }
        }
        return new Result(false, maxAttempts, last);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Outcome of a retry run. */
    public record Result(boolean succeeded, int attempts, RuntimeException lastError) {
    }
}
