package com.homefix.auth.password;

import java.time.Duration;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * Redis-backed {@link LoginAttemptStore}.
 *
 * <p>Key layout (all namespaced under {@code auth:pwd:}):
 * <ul>
 *   <li>{@code auth:pwd:fail:{username}} -> counter, TTL = the failure window</li>
 *   <li>{@code auth:pwd:lock:{username}} -> "1", TTL = the lockout window</li>
 * </ul>
 *
 * <p>As with the OTP store, leaning on Redis TTLs means both windows are enforced by key
 * expiry rather than an application-side clock, so a restart cannot reset a lockout.
 */
@Repository
public class RedisLoginAttemptStore implements LoginAttemptStore {

    private static final String FAIL_PREFIX = "auth:pwd:fail:";
    private static final String LOCK_PREFIX = "auth:pwd:lock:";

    private final StringRedisTemplate redis;

    public RedisLoginAttemptStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public boolean isLocked(String username) {
        return Boolean.TRUE.equals(redis.hasKey(lockKey(username)));
    }

    @Override
    public Duration lockRemaining(String username) {
        Long seconds = redis.getExpire(lockKey(username));
        if (seconds == null || seconds <= 0) {
            return Duration.ZERO;
        }
        return Duration.ofSeconds(seconds);
    }

    @Override
    public int recordFailure(String username, Duration window) {
        String key = failKey(username);
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1L) {
            // First failure in this window - start the expiry clock.
            redis.expire(key, window);
        }
        return count == null ? 0 : count.intValue();
    }

    @Override
    public void lock(String username, Duration lockout) {
        clearFailures(username);
        redis.opsForValue().set(lockKey(username), "1", lockout);
    }

    @Override
    public void clearFailures(String username) {
        redis.delete(failKey(username));
    }

    private String failKey(String username) {
        return FAIL_PREFIX + username;
    }

    private String lockKey(String username) {
        return LOCK_PREFIX + username;
    }
}
