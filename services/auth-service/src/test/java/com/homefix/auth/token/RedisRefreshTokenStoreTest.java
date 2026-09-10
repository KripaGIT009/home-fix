package com.homefix.auth.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * Unit tests for {@link RedisRefreshTokenStore} — the Redis-backed refresh-token store that
 * powers rotation, replay detection, and family invalidation (Requirement 1.9, 1.10, Property 26).
 *
 * <p>Uses a mocked {@link StringRedisTemplate} so the token/family key layout, the
 * {@code subject|familyId|used} encoding, and the family-wide revoke are exercised without Redis.
 */
@ExtendWith(MockitoExtension.class)
class RedisRefreshTokenStoreTest {

    private static final String TOKEN = "refresh-token-abc";
    private static final String TOKEN_KEY = "auth:refresh:" + TOKEN;
    private static final String FAMILY_ID = "family-1";
    private static final String FAMILY_KEY = "auth:family:" + FAMILY_ID;
    private static final Duration TTL = Duration.ofDays(30);

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private ValueOperations<String, String> valueOps;

    @Mock
    private SetOperations<String, String> setOps;

    private RedisRefreshTokenStore store;

    @BeforeEach
    void setUp() {
        lenient().when(redis.opsForValue()).thenReturn(valueOps);
        lenient().when(redis.opsForSet()).thenReturn(setOps);
        store = new RedisRefreshTokenStore(redis);
    }

    @Test
    void save_writesEncodedRecordAndIndexesFamilyWithTtl() {
        store.save(TOKEN, "user-1", FAMILY_ID, TTL);

        verify(valueOps).set(TOKEN_KEY, "user-1|" + FAMILY_ID + "|false", TTL);
        verify(setOps).add(FAMILY_KEY, TOKEN);
        verify(redis).expire(FAMILY_KEY, TTL);
    }

    @Test
    void find_missingToken_returnsEmpty() {
        when(valueOps.get(TOKEN_KEY)).thenReturn(null);

        assertThat(store.find(TOKEN)).isEmpty();
    }

    @Test
    void find_presentToken_decodesRecord() {
        when(valueOps.get(TOKEN_KEY)).thenReturn("user-1|" + FAMILY_ID + "|true");

        Optional<RefreshTokenRecord> record = store.find(TOKEN);

        assertThat(record).isPresent();
        assertThat(record.get().subject()).isEqualTo("user-1");
        assertThat(record.get().familyId()).isEqualTo(FAMILY_ID);
        assertThat(record.get().used()).isTrue();
    }

    @Test
    void find_freshTokenEncoding_decodesAsUnused() {
        when(valueOps.get(TOKEN_KEY)).thenReturn("user-2|" + FAMILY_ID + "|false");

        assertThat(store.find(TOKEN)).get()
                .satisfies(r -> assertThat(r.used()).isFalse());
    }

    @Test
    void markUsed_missingToken_isNoOp() {
        when(valueOps.get(TOKEN_KEY)).thenReturn(null);

        store.markUsed(TOKEN, TTL);

        verify(valueOps, org.mockito.Mockito.never())
                .set(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(Duration.class));
    }

    @Test
    void markUsed_presentToken_rewritesWithUsedFlagTrue() {
        when(valueOps.get(TOKEN_KEY)).thenReturn("user-1|" + FAMILY_ID + "|false");

        store.markUsed(TOKEN, TTL);

        verify(valueOps).set(TOKEN_KEY, "user-1|" + FAMILY_ID + "|true", TTL);
    }

    @Test
    void revoke_deletesTokenKey() {
        store.revoke(TOKEN);

        verify(redis).delete(TOKEN_KEY);
    }

    @Test
    void revokeFamily_deletesEveryMemberAndTheFamilyIndex() {
        when(setOps.members(FAMILY_KEY)).thenReturn(Set.of("t1", "t2"));

        store.revokeFamily(FAMILY_ID);

        verify(redis).delete("auth:refresh:t1");
        verify(redis).delete("auth:refresh:t2");
        verify(redis).delete(FAMILY_KEY);
    }

    @Test
    void revokeFamily_noMembers_stillDeletesFamilyIndex() {
        when(setOps.members(FAMILY_KEY)).thenReturn(null);

        store.revokeFamily(FAMILY_ID);

        verify(redis).delete(FAMILY_KEY);
    }
}
