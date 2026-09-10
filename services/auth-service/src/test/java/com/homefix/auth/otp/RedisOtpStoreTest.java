package com.homefix.auth.otp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * Unit tests for {@link RedisOtpStore} — the Redis-backed OTP session, lockout, and rate-limit
 * store (Requirement 1.3, 1.4, 23.4, Property 25).
 *
 * <p>Uses a mocked {@link StringRedisTemplate} so the key layout and TTL semantics are exercised
 * without a live Redis.
 */
@ExtendWith(MockitoExtension.class)
class RedisOtpStoreTest {

    private static final String PHONE = "+919876543210";
    private static final String SESSION_KEY = "auth:otp:session:" + PHONE;
    private static final String LOCK_KEY = "auth:otp:lock:" + PHONE;
    private static final String RATE_KEY = "auth:otp:rate:" + PHONE;

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private HashOperations<String, Object, Object> hashOps;

    @Mock
    private ValueOperations<String, String> valueOps;

    private RedisOtpStore store;

    @BeforeEach
    void setUp() {
        lenient().when(redis.opsForHash()).thenReturn(hashOps);
        lenient().when(redis.opsForValue()).thenReturn(valueOps);
        store = new RedisOtpStore(redis);
    }

    @Test
    void saveSession_writesHashFieldsAndSetsTtl() {
        store.saveSession(PHONE, new OtpSession("hash-value", 0, "CUSTOMER"), Duration.ofMinutes(5));

        verify(redis).delete(SESSION_KEY);
        verify(hashOps).put(SESSION_KEY, "codeHash", "hash-value");
        verify(hashOps).put(SESSION_KEY, "attempts", "0");
        verify(hashOps).put(SESSION_KEY, "role", "CUSTOMER");
        verify(redis).expire(SESSION_KEY, Duration.ofMinutes(5));
    }

    @Test
    void findSession_missingCodeHash_returnsEmpty() {
        when(hashOps.get(SESSION_KEY, "codeHash")).thenReturn(null);

        assertThat(store.findSession(PHONE)).isEmpty();
    }

    @Test
    void findSession_presentSession_returnsDecodedSession() {
        when(hashOps.get(SESSION_KEY, "codeHash")).thenReturn("hash-value");
        when(hashOps.get(SESSION_KEY, "attempts")).thenReturn("3");
        when(hashOps.get(SESSION_KEY, "role")).thenReturn("SERVICE_PROVIDER");

        Optional<OtpSession> session = store.findSession(PHONE);

        assertThat(session).isPresent();
        assertThat(session.get().codeHash()).isEqualTo("hash-value");
        assertThat(session.get().attempts()).isEqualTo(3);
        assertThat(session.get().role()).isEqualTo("SERVICE_PROVIDER");
    }

    @Test
    void findSession_nullAttemptsAndRole_defaultsToZeroAttemptsAndNullRole() {
        when(hashOps.get(SESSION_KEY, "codeHash")).thenReturn("hash-value");
        when(hashOps.get(SESSION_KEY, "attempts")).thenReturn(null);
        when(hashOps.get(SESSION_KEY, "role")).thenReturn(null);

        Optional<OtpSession> session = store.findSession(PHONE);

        assertThat(session).isPresent();
        assertThat(session.get().attempts()).isZero();
        assertThat(session.get().role()).isNull();
    }

    @Test
    void incrementAttempts_returnsUpdatedCount() {
        when(hashOps.increment(SESSION_KEY, "attempts", 1L)).thenReturn(2L);

        assertThat(store.incrementAttempts(PHONE)).isEqualTo(2);
    }

    @Test
    void incrementAttempts_nullResult_returnsZero() {
        when(hashOps.increment(SESSION_KEY, "attempts", 1L)).thenReturn(null);

        assertThat(store.incrementAttempts(PHONE)).isZero();
    }

    @Test
    void clearSession_deletesSessionKey() {
        store.clearSession(PHONE);

        verify(redis).delete(SESSION_KEY);
    }

    @Test
    void lock_clearsSessionAndSetsLockKeyWithTtl() {
        store.lock(PHONE, Duration.ofMinutes(30));

        verify(redis).delete(SESSION_KEY);
        verify(valueOps).set(LOCK_KEY, "1", Duration.ofMinutes(30));
    }

    @Test
    void isLocked_reflectsLockKeyPresence() {
        when(redis.hasKey(LOCK_KEY)).thenReturn(Boolean.TRUE);
        assertThat(store.isLocked(PHONE)).isTrue();

        when(redis.hasKey(LOCK_KEY)).thenReturn(Boolean.FALSE);
        assertThat(store.isLocked(PHONE)).isFalse();
    }

    @Test
    void lockRemaining_positiveTtl_returnsDuration() {
        when(redis.getExpire(LOCK_KEY)).thenReturn(1800L);

        assertThat(store.lockRemaining(PHONE)).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void lockRemaining_noTtl_returnsZero() {
        when(redis.getExpire(LOCK_KEY)).thenReturn(null);
        assertThat(store.lockRemaining(PHONE)).isEqualTo(Duration.ZERO);

        when(redis.getExpire(LOCK_KEY)).thenReturn(-1L);
        assertThat(store.lockRemaining(PHONE)).isEqualTo(Duration.ZERO);
    }

    @Test
    void recordRequestAndCount_firstRequest_startsExpiryClock() {
        when(valueOps.increment(RATE_KEY)).thenReturn(1L);

        long count = store.recordRequestAndCount(PHONE, Duration.ofHours(1));

        assertThat(count).isEqualTo(1L);
        verify(redis).expire(RATE_KEY, Duration.ofHours(1));
    }

    @Test
    void recordRequestAndCount_subsequentRequest_doesNotResetExpiry() {
        when(valueOps.increment(RATE_KEY)).thenReturn(4L);

        long count = store.recordRequestAndCount(PHONE, Duration.ofHours(1));

        assertThat(count).isEqualTo(4L);
        verify(redis, org.mockito.Mockito.never()).expire(eq(RATE_KEY), any(Duration.class));
    }

    @Test
    void recordRequestAndCount_nullResult_returnsZero() {
        when(valueOps.increment(RATE_KEY)).thenReturn(null);

        assertThat(store.recordRequestAndCount(PHONE, Duration.ofHours(1))).isZero();
    }
}
