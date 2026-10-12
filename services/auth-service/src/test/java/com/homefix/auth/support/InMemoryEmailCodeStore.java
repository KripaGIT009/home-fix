package com.homefix.auth.support;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import com.homefix.auth.emailauth.CodePurpose;
import com.homefix.auth.emailauth.EmailCodeStore;

/**
 * Deterministic in-memory {@link EmailCodeStore} for unit tests. Time moves only through
 * {@link #advance(Duration)}, so code lifetimes, cooldowns and hourly windows expire without waits.
 */
public class InMemoryEmailCodeStore implements EmailCodeStore {

    private record Expiring<T>(T value, Instant expiresAt) {
    }

    private final Map<String, Expiring<StoredCode>> codes = new HashMap<>();
    private final Map<String, Expiring<Long>> counters = new HashMap<>();
    private final Map<String, Instant> cooldowns = new HashMap<>();

    private Instant now = Instant.parse("2026-10-01T00:00:00Z");

    public void advance(Duration duration) {
        now = now.plus(duration);
    }

    private static String key(CodePurpose purpose, String subject) {
        return purpose + ":" + subject;
    }

    @Override
    public void saveCode(CodePurpose purpose, String subject, StoredCode code, Duration ttl) {
        codes.put(key(purpose, subject), new Expiring<>(code, now.plus(ttl)));
    }

    @Override
    public Optional<StoredCode> findCode(CodePurpose purpose, String subject) {
        Expiring<StoredCode> entry = codes.get(key(purpose, subject));
        if (entry == null || !entry.expiresAt().isAfter(now)) {
            return Optional.empty();
        }
        return Optional.of(entry.value());
    }

    @Override
    public int incrementAttempts(CodePurpose purpose, String subject) {
        Expiring<StoredCode> entry = codes.get(key(purpose, subject));
        if (entry == null) {
            return 0;
        }
        StoredCode next = new StoredCode(entry.value().codeHash(), entry.value().attempts() + 1,
                entry.value().payload());
        codes.put(key(purpose, subject), new Expiring<>(next, entry.expiresAt()));
        return next.attempts();
    }

    @Override
    public boolean deleteCode(CodePurpose purpose, String subject) {
        Expiring<StoredCode> removed = codes.remove(key(purpose, subject));
        return removed != null && removed.expiresAt().isAfter(now);
    }

    @Override
    public long countInWindow(String bucket, Duration window) {
        Expiring<Long> current = counters.get(bucket);
        long next = (current == null || !current.expiresAt().isAfter(now)) ? 1 : current.value() + 1;
        Instant expiry = next == 1 ? now.plus(window) : current.expiresAt();
        counters.put(bucket, new Expiring<>(next, expiry));
        return next;
    }

    @Override
    public boolean tryStartCooldown(String key, Duration cooldown) {
        Instant until = cooldowns.get(key);
        if (until != null && until.isAfter(now)) {
            return false;
        }
        cooldowns.put(key, now.plus(cooldown));
        return true;
    }

    @Override
    public Duration cooldownRemaining(String key) {
        Instant until = cooldowns.get(key);
        return until == null || !until.isAfter(now) ? Duration.ZERO : Duration.between(now, until);
    }
}
