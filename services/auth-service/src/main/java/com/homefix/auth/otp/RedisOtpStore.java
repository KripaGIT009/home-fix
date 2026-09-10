package com.homefix.auth.otp;

import java.time.Duration;
import java.util.Optional;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * Redis-backed {@link OtpStore}.
 *
 * <p>Key layout (all namespaced under {@code auth:otp:}):
 * <ul>
 *   <li>{@code auth:otp:session:{phone}}  -> hash {codeHash, attempts, role}, TTL = OTP window</li>
 *   <li>{@code auth:otp:lock:{phone}}     -> "1", TTL = lockout window (Property 25)</li>
 *   <li>{@code auth:otp:rate:{phone}}     -> counter, TTL = rate-limit window (Requirement 23.4)</li>
 * </ul>
 *
 * <p>Using Redis TTLs means the 5-minute OTP expiry and the exact 30-minute lockout are
 * enforced by key expiry rather than application-side clock checks.
 */
@Repository
public class RedisOtpStore implements OtpStore {

    private static final String SESSION_PREFIX = "auth:otp:session:";
    private static final String LOCK_PREFIX = "auth:otp:lock:";
    private static final String RATE_PREFIX = "auth:otp:rate:";

    private static final String FIELD_CODE_HASH = "codeHash";
    private static final String FIELD_ATTEMPTS = "attempts";
    private static final String FIELD_ROLE = "role";

    private final StringRedisTemplate redis;

    public RedisOtpStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void saveSession(String mobileNumber, OtpSession session, Duration ttl) {
        String key = sessionKey(mobileNumber);
        redis.delete(key);
        redis.opsForHash().put(key, FIELD_CODE_HASH, session.codeHash());
        redis.opsForHash().put(key, FIELD_ATTEMPTS, Integer.toString(session.attempts()));
        redis.opsForHash().put(key, FIELD_ROLE, session.role());
        redis.expire(key, ttl);
    }

    @Override
    public Optional<OtpSession> findSession(String mobileNumber) {
        String key = sessionKey(mobileNumber);
        Object codeHash = redis.opsForHash().get(key, FIELD_CODE_HASH);
        if (codeHash == null) {
            return Optional.empty();
        }
        Object attempts = redis.opsForHash().get(key, FIELD_ATTEMPTS);
        Object role = redis.opsForHash().get(key, FIELD_ROLE);
        int attemptCount = attempts == null ? 0 : Integer.parseInt(attempts.toString());
        return Optional.of(new OtpSession(codeHash.toString(), attemptCount,
                role == null ? null : role.toString()));
    }

    @Override
    public int incrementAttempts(String mobileNumber) {
        String key = sessionKey(mobileNumber);
        Long updated = redis.opsForHash().increment(key, FIELD_ATTEMPTS, 1L);
        return updated == null ? 0 : updated.intValue();
    }

    @Override
    public void clearSession(String mobileNumber) {
        redis.delete(sessionKey(mobileNumber));
    }

    @Override
    public void lock(String mobileNumber, Duration lockout) {
        // Clear the spent session and set the lock key with a TTL equal to the lockout window.
        clearSession(mobileNumber);
        redis.opsForValue().set(lockKey(mobileNumber), "1", lockout);
    }

    @Override
    public boolean isLocked(String mobileNumber) {
        return Boolean.TRUE.equals(redis.hasKey(lockKey(mobileNumber)));
    }

    @Override
    public Duration lockRemaining(String mobileNumber) {
        Long seconds = redis.getExpire(lockKey(mobileNumber));
        if (seconds == null || seconds <= 0) {
            return Duration.ZERO;
        }
        return Duration.ofSeconds(seconds);
    }

    @Override
    public long recordRequestAndCount(String mobileNumber, Duration window) {
        String key = rateKey(mobileNumber);
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1L) {
            // First request in this window — start the expiry clock.
            redis.expire(key, window);
        }
        return count == null ? 0L : count;
    }

    private String sessionKey(String mobileNumber) {
        return SESSION_PREFIX + mobileNumber;
    }

    private String lockKey(String mobileNumber) {
        return LOCK_PREFIX + mobileNumber;
    }

    private String rateKey(String mobileNumber) {
        return RATE_PREFIX + mobileNumber;
    }
}
