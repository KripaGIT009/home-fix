package com.homefix.auth.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.connection.DataType;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * {@link LegacyRefreshTokenCleanup}: removes refresh tokens stored in clear by earlier versions
 * (keys and family-set members) while leaving the hashed keys, other family members and unrelated
 * keys alone, iterating with SCAN, and never failing startup. Redis is mocked.
 */
class LegacyRefreshTokenCleanupTest {

    private static final String PREFIX = "auth:refresh:";

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final SetOperations<String, String> sets = mock(SetOperations.class);
    private final LegacyRefreshTokenCleanup cleanup = new LegacyRefreshTokenCleanup(redis);

    private final String legacyA = legacyToken();
    private final String legacyB = legacyToken();
    private final String hashed = RedisRefreshTokenStore.hash(legacyToken());

    @BeforeEach
    void setUp() {
        when(redis.opsForSet()).thenReturn(sets);
    }

    @Test
    void recognisesTheLegacyFormatButNeverAHash() {
        assertThat(LegacyRefreshTokenCleanup.isLegacyToken(legacyA)).isTrue();
        assertThat(LegacyRefreshTokenCleanup.isLegacyToken(hashed)).isFalse();
        assertThat(LegacyRefreshTokenCleanup.isLegacyToken(UUID.randomUUID().toString())).isFalse();
        assertThat(LegacyRefreshTokenCleanup.isLegacyToken(legacyA + "x")).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void deletesOnlyLegacyTokenKeys() {
        scanReturns(PREFIX, PREFIX + legacyA, PREFIX + hashed, PREFIX + legacyB);
        scanReturns("auth:family:");

        LegacyRefreshTokenCleanup.Result result = cleanup.cleanUp();

        ArgumentCaptor<Collection<String>> deleted = ArgumentCaptor.forClass(Collection.class);
        verify(redis).delete(deleted.capture());
        assertThat(deleted.getValue()).containsExactlyInAnyOrder(PREFIX + legacyA, PREFIX + legacyB);
        assertThat(result.familyMembers()).isZero();
    }

    @Test
    void removesOnlyLegacyMembersFromFamilySetsAndSkipsOtherTypes() {
        scanReturns(PREFIX);
        scanReturns("auth:family:", "auth:family:old", "auth:family:new", "auth:family:odd");
        when(redis.type("auth:family:old")).thenReturn(DataType.SET);
        when(redis.type("auth:family:new")).thenReturn(DataType.SET);
        when(redis.type("auth:family:odd")).thenReturn(DataType.STRING);
        when(sets.members("auth:family:old")).thenReturn(Set.of(legacyA, legacyB));
        when(sets.members("auth:family:new")).thenReturn(Set.of(hashed));
        when(sets.remove(eq("auth:family:old"), any(Object[].class))).thenReturn(2L);

        LegacyRefreshTokenCleanup.Result result = cleanup.cleanUp();

        ArgumentCaptor<Object[]> removed = ArgumentCaptor.forClass(Object[].class);
        verify(sets).remove(eq("auth:family:old"), removed.capture());
        assertThat(removed.getValue()).containsExactlyInAnyOrder(legacyA, legacyB);
        verify(sets, never()).remove(eq("auth:family:new"), any(Object[].class));
        verify(sets, never()).members("auth:family:odd");
        assertThat(result.familyMembers()).isEqualTo(2);
        // Nothing legacy under auth:refresh:, so no key deletion at all.
        verify(redis, never()).delete(anyString());
    }

    @Test
    void scansBothPrefixesNeverTheWholeKeyspace() {
        scanReturns(PREFIX);
        scanReturns("auth:family:");

        cleanup.cleanUp();

        ArgumentCaptor<ScanOptions> options = ArgumentCaptor.forClass(ScanOptions.class);
        verify(redis, org.mockito.Mockito.times(2)).scan(options.capture());
        assertThat(options.getAllValues()).extracting(ScanOptions::getPattern)
                .containsExactly(PREFIX + "*", "auth:family:*");
    }

    @Test
    void aRedisFailureIsLoggedNotThrown() {
        when(redis.scan(any(ScanOptions.class))).thenThrow(new IllegalStateException("redis down"));

        cleanup.run(null);
    }

    // ---------------------------------------------------------------------

    /** Refresh tokens as TokenService mints them. */
    private static String legacyToken() {
        return UUID.randomUUID().toString() + UUID.randomUUID().toString();
    }

    @SuppressWarnings("unchecked")
    private void scanReturns(String prefix, String... keys) {
        Iterator<String> it = List.of(keys).iterator();
        Cursor<String> cursor = mock(Cursor.class);
        when(cursor.hasNext()).thenAnswer(inv -> it.hasNext());
        when(cursor.next()).thenAnswer(inv -> it.next());
        when(redis.scan(argThat((ScanOptions o) -> o != null && (prefix + "*").equals(o.getPattern()))))
                .thenReturn(cursor);
    }
}
