package com.homefix.dispatch.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.homefix.dispatch.domain.JobOffer;
import com.homefix.dispatch.domain.OfferRound;
import com.homefix.dispatch.domain.OfferStatus;
import com.homefix.dispatch.port.JobOfferStore.Change;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Unit tests for {@link RedisJobOfferStore} against a mocked {@link StringRedisTemplate}, the way
 * {@link RedisDistributedLockAdapterTest} tests the lock: session callbacks are run against mocked
 * operations so the exact command sequence can be checked. Covers the hash encoding of a whole
 * round (and the single-offer layout written before waves), indexing of newly offered providers,
 * pruning of stale index entries, and the WATCH/MULTI/EXEC compare-and-set including its retry on a
 * discarded transaction (Requirements 8.5-8.7).
 */
@SuppressWarnings({"unchecked", "rawtypes"})
class RedisJobOfferStoreTest {

    private static final Instant T0 = Instant.parse("2026-10-02T10:00:00Z");
    private static final Duration WINDOW = Duration.ofSeconds(60);
    private static final Duration TTL = Duration.ofSeconds(90);

    private StringRedisTemplate redis;
    private RedisOperations<String, String> session;
    private HashOperations<String, Object, Object> hash;
    private SetOperations<String, String> set;
    private RedisJobOfferStore store;

    private final UUID bookingId = UUID.randomUUID();
    private final UUID provider = UUID.randomUUID();
    private final UUID second = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        session = mock(RedisOperations.class);
        hash = mock(HashOperations.class);
        set = mock(SetOperations.class);
        when(redis.opsForHash()).thenReturn((HashOperations) hash);
        when(redis.opsForSet()).thenReturn(set);
        when(session.opsForHash()).thenReturn((HashOperations) hash);
        when(session.opsForSet()).thenReturn((SetOperations) set);
        when(redis.execute(any(SessionCallback.class)))
                .thenAnswer(inv -> ((SessionCallback) inv.getArgument(0)).execute(session));
        store = new RedisJobOfferStore(redis);
    }

    private JobOffer pending(UUID to) {
        return JobOffer.pending(bookingId, to, T0, WINDOW);
    }

    private OfferRound round(JobOffer... offers) {
        return new OfferRound(bookingId, List.of(offers));
    }

    private OfferRound full() {
        Instant scheduled = Instant.parse("2026-10-03T08:00:00Z");
        UUID subcategory = UUID.randomUUID();
        return round(
                new JobOffer(bookingId, provider, OfferStatus.ACCEPTED, T0, T0.plus(WINDOW),
                        T0.plusSeconds(12), true, subcategory, "HFX-2026-0000321", scheduled),
                new JobOffer(bookingId, second, OfferStatus.WITHDRAWN, T0, T0.plus(WINDOW),
                        T0.plusSeconds(12), true, subcategory, "HFX-2026-0000321", scheduled));
    }

    private static Map<Object, Object> stored(OfferRound round) {
        return new HashMap<>(RedisJobOfferStore.toHash(round));
    }

    // ---- encoding ------------------------------------------------------------------------------

    @Test
    void hashEncoding_roundTripsEveryOfferAndField() {
        OfferRound round = full();

        assertThat(RedisJobOfferStore.fromHash(bookingId, stored(round))).isEqualTo(round);
    }

    @Test
    void hashEncoding_keepsOneFieldPerProviderAndOmitsAbsentOptionalFields() {
        Map<String, String> fields = RedisJobOfferStore.toHash(round(pending(provider), pending(second)));

        assertThat(fields).containsKeys("offer:" + provider, "offer:" + second);
        assertThat(fields).doesNotContainKeys("subcategoryId", "reference", "scheduledAt");
        assertThat(RedisJobOfferStore.fromHash(bookingId, new HashMap<>(fields)))
                .isEqualTo(round(pending(provider), pending(second)));
    }

    @Test
    void hashDecoding_readsTheSingleOfferLayoutWrittenBeforeWaves() {
        Map<Object, Object> legacy = new HashMap<>(Map.of(
                "bookingId", bookingId.toString(),
                "providerId", provider.toString(),
                "status", "PENDING",
                "offeredAt", Long.toString(T0.toEpochMilli()),
                "expiresAt", Long.toString(T0.plus(WINDOW).toEpochMilli()),
                "emergency", "false"));

        assertThat(RedisJobOfferStore.fromHash(bookingId, legacy)).isEqualTo(round(pending(provider)));
    }

    // ---- reads ---------------------------------------------------------------------------------

    @Test
    void find_decodesTheStoredHash() {
        OfferRound round = full();
        when(hash.entries(RedisJobOfferStore.offerKey(bookingId))).thenReturn(stored(round));

        assertThat(store.find(bookingId)).contains(round);
        assertThat(RedisJobOfferStore.offerKey(bookingId)).isEqualTo("dispatch:offer:booking:" + bookingId);
    }

    @Test
    void find_isEmptyWhenNothingIsStored() {
        when(hash.entries(anyString())).thenReturn(Map.of());

        assertThat(store.find(bookingId)).isEmpty();
    }

    @Test
    void findByProvider_returnsTheirOffersAndPrunesStaleIndexEntries() {
        UUID expired = UUID.randomUUID();
        String indexKey = RedisJobOfferStore.indexKey(provider);
        Set<String> members = new LinkedHashSet<>(List.of(bookingId.toString(), expired.toString(), "not-a-uuid"));
        when(set.members(indexKey)).thenReturn(members);
        when(hash.entries(RedisJobOfferStore.offerKey(bookingId)))
                .thenReturn(stored(round(pending(provider), pending(second))));
        when(hash.entries(RedisJobOfferStore.offerKey(expired))).thenReturn(Map.of());

        List<JobOffer> offers = store.findByProvider(provider);

        assertThat(offers).containsExactly(pending(provider));
        verify(set).remove(indexKey, expired.toString());
        verify(set).remove(indexKey, "not-a-uuid");
        verify(set, never()).remove(indexKey, bookingId.toString());
        assertThat(indexKey).isEqualTo("dispatch:offers:provider:" + provider);
    }

    @Test
    void findByProvider_isEmptyWithoutAnIndex() {
        when(set.members(anyString())).thenReturn(null);

        assertThat(store.findByProvider(provider)).isEmpty();
    }

    // ---- compare-and-set -----------------------------------------------------------------------

    @Test
    void openingAWave_writesTheRoundAndIndexesEveryNewProviderWithTheTtl() {
        String key = RedisJobOfferStore.offerKey(bookingId);
        when(hash.entries(key)).thenReturn(Map.of());
        // What a real commit returns: HMSET's status reply is dropped by RedisTemplate.exec(), so
        // only the HLEN count survives.
        when(session.exec()).thenReturn(List.of(4L));
        OfferRound wave = round(pending(provider), pending(second));

        Change change = store.update(bookingId, r -> r.open(wave.offers()), TTL);

        assertThat(change).isEqualTo(new Change(OfferRound.empty(bookingId), wave));
        InOrder order = inOrder(session, hash, set);
        order.verify(session).watch(key);
        order.verify(hash).entries(key);
        order.verify(session).multi();
        order.verify(hash).putAll(key, RedisJobOfferStore.toHash(wave));
        order.verify(session).expire(key, TTL);
        order.verify(hash).size(key);
        order.verify(session).exec();
        verify(set).add(RedisJobOfferStore.indexKey(provider), bookingId.toString());
        verify(set).add(RedisJobOfferStore.indexKey(second), bookingId.toString());
        verify(session).expire(RedisJobOfferStore.indexKey(provider), TTL);
        verify(session).expire(RedisJobOfferStore.indexKey(second), TTL);
    }

    @Test
    void aDecision_writesWithoutTouchingTheExpiryOrTheIndex() {
        String key = RedisJobOfferStore.offerKey(bookingId);
        when(hash.entries(key)).thenReturn(stored(round(pending(provider), pending(second))));
        when(session.exec()).thenReturn(List.of(4L));

        Change change = store.update(bookingId,
                r -> r.decide(provider, OfferStatus.ACCEPTED, T0.plusSeconds(5)), null);

        assertThat(change.after().accepted()).get().extracting(JobOffer::providerId).isEqualTo(provider);
        assertThat(change.after().offerTo(second)).get().extracting(JobOffer::status).isEqualTo(OfferStatus.WITHDRAWN);
        verify(hash).putAll(key, RedisJobOfferStore.toHash(change.after()));
        verify(session, never()).expire(anyString(), any(Duration.class));
        verify(set, never()).add(anyString(), anyString());
    }

    @Test
    void update_thatChangesNothing_writesNothing() {
        when(hash.entries(anyString())).thenReturn(stored(round(pending(provider))));

        Change change = store.update(bookingId, r -> r, null);

        assertThat(change).isEqualTo(new Change(round(pending(provider)), round(pending(provider))));
        verify(session).unwatch();
        verify(session, never()).multi();
        verify(hash, never()).putAll(anyString(), anyMap());
    }

    @Test
    void update_ofNothingStored_withATransitionThatAddsNothing_writesNothing() {
        when(hash.entries(anyString())).thenReturn(Map.of());

        Change change = store.update(bookingId, r -> r.close(null, OfferStatus.WITHDRAWN, T0), null);

        assertThat(change.after().isEmpty()).isTrue();
        verify(session, never()).multi();
    }

    @Test
    void update_retriesWhenAConcurrentWriteDiscardsTheTransaction() {
        String key = RedisJobOfferStore.offerKey(bookingId);
        OfferRound acceptedMeanwhile = round(pending(provider), pending(second))
                .decide(second, OfferStatus.ACCEPTED, T0.plusSeconds(1));
        // First read sees both PENDING but EXEC is discarded; the retry reads the other provider's
        // concurrent accept, and this provider's accept then changes nothing.
        when(hash.entries(key)).thenReturn(stored(round(pending(provider), pending(second))), stored(acceptedMeanwhile));
        when(session.exec()).thenReturn(null);

        Change change = store.update(bookingId,
                r -> r.decide(provider, OfferStatus.ACCEPTED, T0.plusSeconds(2)), null);

        assertThat(change).isEqualTo(new Change(acceptedMeanwhile, acceptedMeanwhile));
        verify(session, times(2)).watch(key);
        verify(session, times(1)).exec();
    }

    @Test
    void update_treatsAnEmptyExecReplyAsAConflictToo() {
        when(hash.entries(anyString())).thenReturn(stored(round(pending(provider))));
        when(session.exec()).thenReturn(List.of(), List.of(3L));

        Change change = store.update(bookingId,
                r -> r.close(List.of(provider), OfferStatus.EXPIRED, T0.plus(WINDOW)), null);

        assertThat(change.after().offerTo(provider)).get().extracting(JobOffer::status).isEqualTo(OfferStatus.EXPIRED);
        verify(session, times(2)).exec();
    }

    @Test
    void update_givesUpAfterTheAttemptLimitUnderContention() {
        when(hash.entries(anyString())).thenReturn(stored(round(pending(provider))));
        when(session.exec()).thenReturn(List.of());

        assertThatThrownBy(() -> store.update(bookingId,
                r -> r.close(null, OfferStatus.WITHDRAWN, T0), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(bookingId.toString());
        verify(session, times(RedisJobOfferStore.MAX_CAS_ATTEMPTS)).exec();
        verify(hash, times(RedisJobOfferStore.MAX_CAS_ATTEMPTS))
                .putAll(eq(RedisJobOfferStore.offerKey(bookingId)), anyMap());
    }
}
