package com.homefix.auth.support;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import com.homefix.auth.otp.OtpSession;
import com.homefix.auth.otp.OtpStore;

/**
 * Deterministic in-memory {@link OtpStore} for unit tests — no Redis required.
 *
 * <p>Time is controlled via {@link #advance(Duration)} so TTL-based expiry (OTP window,
 * lockout window) can be tested without real waits.
 */
public class InMemoryOtpStore implements OtpStore {

    private record Expiring<T>(T value, Instant expiresAt) {
    }

    private final Map<String, Expiring<OtpSession>> sessions = new HashMap<>();
    private final Map<String, Expiring<Boolean>> locks = new HashMap<>();
    private final Map<String, Expiring<Long>> rate = new HashMap<>();

    private Instant now = Instant.parse("2026-01-01T00:00:00Z");

    /** Advances the simulated clock, expiring any keys whose TTL has elapsed. */
    public void advance(Duration duration) {
        now = now.plus(duration);
    }

    private boolean expired(Instant expiresAt) {
        return !now.isBefore(expiresAt);
    }

    @Override
    public void saveSession(String mobileNumber, OtpSession session, Duration ttl) {
        sessions.put(mobileNumber, new Expiring<>(session, now.plus(ttl)));
    }

    @Override
    public Optional<OtpSession> findSession(String mobileNumber) {
        Expiring<OtpSession> entry = sessions.get(mobileNumber);
        if (entry == null || expired(entry.expiresAt())) {
            sessions.remove(mobileNumber);
            return Optional.empty();
        }
        return Optional.of(entry.value());
    }

    @Override
    public int incrementAttempts(String mobileNumber) {
        Expiring<OtpSession> entry = sessions.get(mobileNumber);
        if (entry == null || expired(entry.expiresAt())) {
            return 0;
        }
        OtpSession current = entry.value();
        OtpSession updated = new OtpSession(current.codeHash(), current.attempts() + 1, current.role());
        sessions.put(mobileNumber, new Expiring<>(updated, entry.expiresAt()));
        return updated.attempts();
    }

    @Override
    public void clearSession(String mobileNumber) {
        sessions.remove(mobileNumber);
    }

    @Override
    public void lock(String mobileNumber, Duration lockout) {
        sessions.remove(mobileNumber);
        locks.put(mobileNumber, new Expiring<>(Boolean.TRUE, now.plus(lockout)));
    }

    @Override
    public boolean isLocked(String mobileNumber) {
        Expiring<Boolean> entry = locks.get(mobileNumber);
        if (entry == null || expired(entry.expiresAt())) {
            locks.remove(mobileNumber);
            return false;
        }
        return true;
    }

    @Override
    public Duration lockRemaining(String mobileNumber) {
        Expiring<Boolean> entry = locks.get(mobileNumber);
        if (entry == null || expired(entry.expiresAt())) {
            return Duration.ZERO;
        }
        return Duration.between(now, entry.expiresAt());
    }

    @Override
    public long recordRequestAndCount(String mobileNumber, Duration window) {
        Expiring<Long> entry = rate.get(mobileNumber);
        if (entry == null || expired(entry.expiresAt())) {
            rate.put(mobileNumber, new Expiring<>(1L, now.plus(window)));
            return 1L;
        }
        long next = entry.value() + 1L;
        rate.put(mobileNumber, new Expiring<>(next, entry.expiresAt()));
        return next;
    }
}
