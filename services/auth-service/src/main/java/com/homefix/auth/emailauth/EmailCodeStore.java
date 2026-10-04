package com.homefix.auth.emailauth;

import java.time.Duration;
import java.util.Optional;

/**
 * Storage for emailed codes and the counters that rate-limit them (email-auth design D3). Backed by
 * Redis beside the OTP store, so expiry is a key TTL; abstracted so the flows can be unit-tested
 * against an in-memory fake.
 */
public interface EmailCodeStore {

    /** A pending code: its salted hash, wrong attempts so far, and an optional payload. */
    record StoredCode(String codeHash, int attempts, String payload) {
    }

    /** Stores a code for {@code (purpose, subject)}, replacing any earlier one and its attempts. */
    void saveCode(CodePurpose purpose, String subject, StoredCode code, Duration ttl);

    Optional<StoredCode> findCode(CodePurpose purpose, String subject);

    /** Counts one wrong attempt and returns the new total. */
    int incrementAttempts(CodePurpose purpose, String subject);

    /**
     * Removes the code. Returns true only for the caller that removed it, which makes a correct code
     * single-use even when submitted twice at once (Property EA2).
     */
    boolean deleteCode(CodePurpose purpose, String subject);

    /**
     * Counts one event in {@code bucket}; the first event starts a window of {@code window}.
     *
     * @return the number of events in the current window, this one included
     */
    long countInWindow(String bucket, Duration window);

    /**
     * Starts a cooldown on {@code key} unless one is running.
     *
     * @return true if the cooldown was started (the action may go ahead)
     */
    boolean tryStartCooldown(String key, Duration cooldown);

    /** Time left on a running cooldown, or zero. */
    Duration cooldownRemaining(String key);
}
