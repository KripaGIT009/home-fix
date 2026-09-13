package com.homefix.auth.support;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import com.homefix.auth.password.LoginAttemptStore;

/**
 * Deterministic in-memory {@link LoginAttemptStore} for unit tests — no Redis required.
 *
 * <p>Time is controlled via {@link #advance(Duration)}, so both the failure window and the
 * lockout window can be expired without real waits. Mirrors {@link InMemoryOtpStore}.
 */
public class InMemoryLoginAttemptStore implements LoginAttemptStore {

    private record Expiring<T>(T value, Instant expiresAt) {
    }

    private final Map<String, Expiring<Integer>> failures = new HashMap<>();
    private final Map<String, Expiring<Boolean>> locks = new HashMap<>();

    private Instant now = Instant.parse("2026-01-01T00:00:00Z");

    /** Advances the simulated clock, expiring any keys whose TTL has elapsed. */
    public void advance(Duration duration) {
        now = now.plus(duration);
        failures.entrySet().removeIf(e -> !e.getValue().expiresAt().isAfter(now));
        locks.entrySet().removeIf(e -> !e.getValue().expiresAt().isAfter(now));
    }

    @Override
    public boolean isLocked(String username) {
        Expiring<Boolean> lock = locks.get(username);
        return lock != null && lock.expiresAt().isAfter(now);
    }

    @Override
    public Duration lockRemaining(String username) {
        Expiring<Boolean> lock = locks.get(username);
        if (lock == null || !lock.expiresAt().isAfter(now)) {
            return Duration.ZERO;
        }
        return Duration.between(now, lock.expiresAt());
    }

    @Override
    public int recordFailure(String username, Duration window) {
        Expiring<Integer> current = failures.get(username);
        // A counter whose window has elapsed starts again from one, matching the Redis
        // implementation where the key has simply expired.
        int next = (current == null || !current.expiresAt().isAfter(now))
                ? 1
                : current.value() + 1;
        Instant expiry = (current == null || !current.expiresAt().isAfter(now))
                ? now.plus(window)
                : current.expiresAt();
        failures.put(username, new Expiring<>(next, expiry));
        return next;
    }

    @Override
    public void lock(String username, Duration lockout) {
        clearFailures(username);
        locks.put(username, new Expiring<>(true, now.plus(lockout)));
    }

    @Override
    public void clearFailures(String username) {
        failures.remove(username);
    }
}
