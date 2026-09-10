package com.homefix.dispatch.adapter;

import com.homefix.dispatch.port.DistributedLockPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.RedisStringCommands.SetOption;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.types.Expiration;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

/**
 * Redis-backed exclusive per-provider offer lock (Requirement 8.11). Acquisition is an atomic
 * {@code SET dispatch:lock:provider:{id} token NX PX ttl}; only the token owner releases the key,
 * using a check-and-delete Lua script so an expired-then-reacquired lock is never released by the
 * previous holder.
 *
 * <p>Registered by {@code DispatchAdaptersConfig} rather than component-scanned: it depends on the
 * auto-configured {@link StringRedisTemplate}, and a {@code @ConditionalOnBean} evaluated during
 * component scanning runs before auto-configuration, so the bean would silently never be created.
 * Unit tests construct an in-memory fake directly.
 */
public class RedisDistributedLockAdapter implements DistributedLockPort {

    private static final Logger log = LoggerFactory.getLogger(RedisDistributedLockAdapter.class);

    static final String KEY_PREFIX = "dispatch:lock:provider:";

    /** Release only if the stored token matches ours (atomic compare-and-delete). */
    private static final String RELEASE_SCRIPT =
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end";

    private final StringRedisTemplate redis;

    public RedisDistributedLockAdapter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public LockHandle tryAcquire(UUID providerId, Duration ttl) {
        String key = KEY_PREFIX + providerId;
        String token = UUID.randomUUID().toString();
        Boolean acquired = redis.execute((RedisCallback<Boolean>) connection ->
                connection.stringCommands().set(
                        key.getBytes(StandardCharsets.UTF_8),
                        token.getBytes(StandardCharsets.UTF_8),
                        Expiration.from(ttl),
                        SetOption.SET_IF_ABSENT));
        if (Boolean.TRUE.equals(acquired)) {
            return new LockHandle(providerId, token, key);
        }
        log.debug("Offer lock already held for provider {}", providerId);
        return null;
    }

    @Override
    public void release(LockHandle handle) {
        if (handle == null) {
            return;
        }
        redis.execute((RedisCallback<Long>) connection ->
                connection.scriptingCommands().eval(
                        RELEASE_SCRIPT.getBytes(StandardCharsets.UTF_8),
                        org.springframework.data.redis.connection.ReturnType.INTEGER,
                        1,
                        handle.key().getBytes(StandardCharsets.UTF_8),
                        handle.token().getBytes(StandardCharsets.UTF_8)));
    }
}
