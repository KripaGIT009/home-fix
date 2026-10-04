package com.homefix.auth.emailauth;

import java.time.Duration;
import java.util.Locale;
import java.util.Optional;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * Redis-backed {@link EmailCodeStore}. Key layout, all under {@code auth:email:}:
 * <ul>
 *   <li>{@code auth:email:code:{purpose}:{subject}} -> hash {codeHash, attempts, payload}, TTL = code lifetime</li>
 *   <li>{@code auth:email:count:{bucket}} -> counter, TTL = window</li>
 *   <li>{@code auth:email:cooldown:{key}} -> "1", TTL = cooldown</li>
 * </ul>
 * Subjects are lower-case addresses or account ids. The raw code is never stored.
 */
@Repository
public class RedisEmailCodeStore implements EmailCodeStore {

    private static final String CODE_PREFIX = "auth:email:code:";
    private static final String COUNT_PREFIX = "auth:email:count:";
    private static final String COOLDOWN_PREFIX = "auth:email:cooldown:";

    private static final String FIELD_HASH = "codeHash";
    private static final String FIELD_ATTEMPTS = "attempts";
    private static final String FIELD_PAYLOAD = "payload";

    private final StringRedisTemplate redis;

    public RedisEmailCodeStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void saveCode(CodePurpose purpose, String subject, StoredCode code, Duration ttl) {
        String key = codeKey(purpose, subject);
        redis.delete(key);
        redis.opsForHash().put(key, FIELD_HASH, code.codeHash());
        redis.opsForHash().put(key, FIELD_ATTEMPTS, Integer.toString(code.attempts()));
        if (code.payload() != null) {
            redis.opsForHash().put(key, FIELD_PAYLOAD, code.payload());
        }
        redis.expire(key, ttl);
    }

    @Override
    public Optional<StoredCode> findCode(CodePurpose purpose, String subject) {
        String key = codeKey(purpose, subject);
        Object hash = redis.opsForHash().get(key, FIELD_HASH);
        if (hash == null) {
            return Optional.empty();
        }
        Object attempts = redis.opsForHash().get(key, FIELD_ATTEMPTS);
        Object payload = redis.opsForHash().get(key, FIELD_PAYLOAD);
        return Optional.of(new StoredCode(hash.toString(),
                attempts == null ? 0 : Integer.parseInt(attempts.toString()),
                payload == null ? null : payload.toString()));
    }

    @Override
    public int incrementAttempts(CodePurpose purpose, String subject) {
        Long updated = redis.opsForHash().increment(codeKey(purpose, subject), FIELD_ATTEMPTS, 1L);
        return updated == null ? 0 : updated.intValue();
    }

    @Override
    public boolean deleteCode(CodePurpose purpose, String subject) {
        return Boolean.TRUE.equals(redis.delete(codeKey(purpose, subject)));
    }

    @Override
    public long countInWindow(String bucket, Duration window) {
        String key = COUNT_PREFIX + bucket;
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redis.expire(key, window);
        }
        return count == null ? 0L : count;
    }

    @Override
    public boolean tryStartCooldown(String key, Duration cooldown) {
        return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(COOLDOWN_PREFIX + key, "1", cooldown));
    }

    @Override
    public Duration cooldownRemaining(String key) {
        Long seconds = redis.getExpire(COOLDOWN_PREFIX + key);
        return seconds == null || seconds <= 0 ? Duration.ZERO : Duration.ofSeconds(seconds);
    }

    private static String codeKey(CodePurpose purpose, String subject) {
        return CODE_PREFIX + purpose.name().toLowerCase(Locale.ROOT) + ":" + subject;
    }
}
