package com.homefix.auth.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.homefix.auth.config.AuthTokenProperties;

/**
 * Unit tests for {@link RedisRefreshTokenStore} — the Redis-backed refresh-token store that
 * powers rotation, replay detection, and family invalidation (Requirement 1.9, 1.10, Property 26).
 *
 * <p>Uses a mocked {@link StringRedisTemplate} so the hashed key layout, the
 * {@code subject|familyId|used} encoding, the script invocations, and the family-wide revoke are
 * exercised without Redis. The Lua scripts' server-side behaviour is not executed here (the test
 * setup has no Redis); what is asserted is that rotation and save go through a single script
 * call rather than separate reads and writes.
 */
@ExtendWith(MockitoExtension.class)
class RedisRefreshTokenStoreTest {

    private static final String TOKEN = "refresh-token-abc";
    private static final String TOKEN_HASH = RedisRefreshTokenStore.hash(TOKEN);
    private static final String TOKEN_KEY = "auth:refresh:" + TOKEN_HASH;
    private static final String FAMILY_ID = "family-1";
    private static final String FAMILY_KEY = "auth:family:" + FAMILY_ID;
    private static final String REVOKED_KEY = "auth:family-revoked:" + FAMILY_ID;
    private static final String SUBJECT_KEY = "auth:subject-families:user-1";
    private static final Duration TTL = Duration.ofDays(30);
    private static final String TTL_MS = Long.toString(TTL.toMillis());

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
        AuthTokenProperties props = new AuthTokenProperties();
        props.setRefreshTtl(TTL);
        store = new RedisRefreshTokenStore(redis, props);
    }

    // ----- key hashing (review 8.2: tokens were stored in clear) -----

    @Test
    void hash_isStableHexSha256AndNeverTheRawToken() {
        assertThat(TOKEN_HASH)
                .hasSize(64)
                .matches("[0-9a-f]{64}")
                .isEqualTo(RedisRefreshTokenStore.hash(TOKEN))
                .doesNotContain(TOKEN);
        assertThat(RedisRefreshTokenStore.hash("other-token")).isNotEqualTo(TOKEN_HASH);
    }

    // ----- save -----

    @Test
    void save_runsOneScriptOverHashedKeysAndReportsStored() {
        when(redis.execute(eq(RedisRefreshTokenStore.SAVE_SCRIPT),
                eq(List.of(TOKEN_KEY, FAMILY_KEY, REVOKED_KEY, SUBJECT_KEY)),
                eq("user-1|" + FAMILY_ID + "|false"), eq(TTL_MS), eq(TOKEN_HASH), eq(FAMILY_ID)))
                .thenReturn(1L);

        assertThat(store.save(TOKEN, "user-1", FAMILY_ID, TTL)).isTrue();

        // No separate non-atomic writes.
        verify(valueOps, never()).set(anyString(), anyString(), org.mockito.ArgumentMatchers.any(Duration.class));
        verify(setOps, never()).add(anyString(), org.mockito.ArgumentMatchers.<String>any());
    }

    @Test
    void save_intoRevokedFamily_reportsNotStored() {
        when(redis.execute(eq(RedisRefreshTokenStore.SAVE_SCRIPT),
                eq(List.of(TOKEN_KEY, FAMILY_KEY, REVOKED_KEY, SUBJECT_KEY)),
                anyString(), anyString(), anyString(), anyString()))
                .thenReturn(0L);

        assertThat(store.save(TOKEN, "user-1", FAMILY_ID, TTL)).isFalse();
    }

    // ----- find -----

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

    // ----- consume (atomic rotation) -----

    @Test
    void consume_missingToken_returnsEmpty() {
        when(redis.execute(eq(RedisRefreshTokenStore.CONSUME_SCRIPT), eq(List.of(TOKEN_KEY)), eq(TTL_MS)))
                .thenReturn(null);

        assertThat(store.consume(TOKEN, TTL)).isEmpty();
    }

    @Test
    void consume_unusedToken_returnsPriorUnusedRecordFromOneScriptCall() {
        when(redis.execute(eq(RedisRefreshTokenStore.CONSUME_SCRIPT), eq(List.of(TOKEN_KEY)), eq(TTL_MS)))
                .thenReturn("user-1|" + FAMILY_ID + "|false");

        assertThat(store.consume(TOKEN, TTL)).get().satisfies(r -> {
            assertThat(r.subject()).isEqualTo("user-1");
            assertThat(r.familyId()).isEqualTo(FAMILY_ID);
            assertThat(r.used()).isFalse();
        });
        // The used flag is flipped server-side by the script, never by a client-side GET + SET.
        verify(valueOps, never()).get(anyString());
        verify(valueOps, never()).set(anyString(), anyString(), org.mockito.ArgumentMatchers.any(Duration.class));
    }

    @Test
    void consume_alreadyUsedToken_returnsUsedRecordSoCallerSeesReplay() {
        when(redis.execute(eq(RedisRefreshTokenStore.CONSUME_SCRIPT), eq(List.of(TOKEN_KEY)), eq(TTL_MS)))
                .thenReturn("user-1|" + FAMILY_ID + "|true");

        assertThat(store.consume(TOKEN, TTL)).get()
                .satisfies(r -> assertThat(r.used()).isTrue());
    }

    @Test
    void consumeScript_flipsOnlyAnUnusedValueAndReturnsThePriorValue() {
        // Guard the script text against an edit that would turn it back into a blind write.
        String lua = RedisRefreshTokenStore.CONSUME_SCRIPT.getScriptAsString();
        assertThat(lua).contains("redis.call('GET', KEYS[1])")
                .contains("== '|true'")
                .contains("redis.call('SET', KEYS[1], base .. '|true', 'PX', ARGV[1])")
                .contains("return current");
        String save = RedisRefreshTokenStore.SAVE_SCRIPT.getScriptAsString();
        assertThat(save).contains("redis.call('EXISTS', KEYS[3]) == 1")
                // The subject index is written in the same script, so no token escapes it.
                .contains("redis.call('SADD', KEYS[4], ARGV[4])");
    }

    // ----- revoke -----

    @Test
    void revoke_deletesHashedTokenKey() {
        store.revoke(TOKEN);

        verify(redis).delete(TOKEN_KEY);
    }

    @Test
    void revokeFamily_marksFamilyRevokedBeforeDeletingMembersAndIndex() {
        when(setOps.members(FAMILY_KEY)).thenReturn(Set.of("h1", "h2"));

        store.revokeFamily(FAMILY_ID);

        InOrder order = inOrder(valueOps, setOps, redis);
        order.verify(valueOps).set(REVOKED_KEY, "1", TTL);
        order.verify(setOps).members(FAMILY_KEY);
        verify(redis).delete("auth:refresh:h1");
        verify(redis).delete("auth:refresh:h2");
        verify(redis).delete(FAMILY_KEY);
    }

    @Test
    void revokeFamily_noMembers_stillMarksAndDeletesFamilyIndex() {
        when(setOps.members(FAMILY_KEY)).thenReturn(null);

        store.revokeFamily(FAMILY_ID);

        verify(valueOps).set(REVOKED_KEY, "1", TTL);
        verify(redis).delete(FAMILY_KEY);
    }

    // ----- revokeAllForSubject (account suspension, Requirement 19.2) -----

    @Test
    void revokeAllForSubject_revokesEveryIndexedFamilyMarkerFirstThenUnindexesThem() {
        when(setOps.members(SUBJECT_KEY)).thenReturn(Set.of("family-a", "family-b"));
        when(setOps.members("auth:family:family-a")).thenReturn(Set.of("ha"));
        when(setOps.members("auth:family:family-b")).thenReturn(Set.of("hb"));

        store.revokeAllForSubject("user-1");

        verify(valueOps).set("auth:family-revoked:family-a", "1", TTL);
        verify(valueOps).set("auth:family-revoked:family-b", "1", TTL);
        verify(redis).delete("auth:refresh:ha");
        verify(redis).delete("auth:refresh:hb");
        // Only the families just revoked leave the index; one added concurrently stays findable.
        verify(setOps).remove(eq(SUBJECT_KEY), org.mockito.ArgumentMatchers.any(Object[].class));
        verify(redis, never()).delete(SUBJECT_KEY);
    }

    @Test
    void revokeAllForSubject_noIndexedFamilies_isANoOp() {
        when(setOps.members(SUBJECT_KEY)).thenReturn(Set.of());

        store.revokeAllForSubject("user-1");

        verify(valueOps, never()).set(anyString(), anyString(), org.mockito.ArgumentMatchers.any(Duration.class));
        verify(setOps, never()).remove(anyString(), org.mockito.ArgumentMatchers.any(Object[].class));
    }
}
