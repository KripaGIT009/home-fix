package com.homefix.dispatch.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.UUID;

import com.homefix.dispatch.port.DistributedLockPort.LockHandle;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Unit tests for {@link RedisDistributedLockAdapter}: acquisition returns a token-bearing handle on
 * a successful atomic SET-NX and {@code null} when the key is already held; release runs the
 * compare-and-delete script only when a handle is present (Requirement 8.11).
 */
class RedisDistributedLockAdapterTest {

    @Test
    @SuppressWarnings("unchecked")
    void tryAcquire_returnsHandleWhenSetNxSucceeds() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisCallback.class))).thenReturn(Boolean.TRUE);
        RedisDistributedLockAdapter adapter = new RedisDistributedLockAdapter(redis);
        UUID provider = UUID.randomUUID();

        LockHandle handle = adapter.tryAcquire(provider, Duration.ofSeconds(60));

        assertThat(handle).isNotNull();
        assertThat(handle.providerId()).isEqualTo(provider);
        assertThat(handle.key()).isEqualTo(RedisDistributedLockAdapter.KEY_PREFIX + provider);
        assertThat(handle.token()).isNotBlank();
    }

    @Test
    @SuppressWarnings("unchecked")
    void tryAcquire_returnsNullWhenKeyAlreadyHeld() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisCallback.class))).thenReturn(Boolean.FALSE);
        RedisDistributedLockAdapter adapter = new RedisDistributedLockAdapter(redis);

        assertThat(adapter.tryAcquire(UUID.randomUUID(), Duration.ofSeconds(60))).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void release_runsCompareAndDeleteScriptForANonNullHandle() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        RedisDistributedLockAdapter adapter = new RedisDistributedLockAdapter(redis);
        LockHandle handle = new LockHandle(UUID.randomUUID(), "tok", "dispatch:lock:provider:x");

        adapter.release(handle);

        verify(redis, times(1)).execute(any(RedisCallback.class));
    }

    @Test
    void release_isNoOpForNullHandle() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        RedisDistributedLockAdapter adapter = new RedisDistributedLockAdapter(redis);

        adapter.release(null); // must not touch Redis or throw
    }
}
