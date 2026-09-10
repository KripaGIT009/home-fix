package com.homefix.payment.idempotency;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis-backed {@link IdempotencyStorePort} keyed by {@code payment:idem:{key}} with a TTL that
 * covers the payment window (Requirement 12.3). Uses {@code SET key value NX EX ttl} semantics via
 * {@code setIfAbsent} so a duplicate concurrent request cannot both create the entry.
 */
public class RedisIdempotencyStoreAdapter implements IdempotencyStorePort {

    private static final String KEY_PREFIX = "payment:idem:";

    private final StringRedisTemplate redis;

    public RedisIdempotencyStoreAdapter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public boolean putIfAbsent(String key, UUID transactionId, Duration ttl) {
        Boolean created = redis.opsForValue()
                .setIfAbsent(redisKey(key), transactionId.toString(), ttl);
        return Boolean.TRUE.equals(created);
    }

    @Override
    public Optional<UUID> find(String key) {
        String value = redis.opsForValue().get(redisKey(key));
        return value == null ? Optional.empty() : Optional.of(UUID.fromString(value));
    }

    private String redisKey(String key) {
        return KEY_PREFIX + key;
    }
}
