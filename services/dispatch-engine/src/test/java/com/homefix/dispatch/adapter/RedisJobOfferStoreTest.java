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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.homefix.dispatch.domain.JobOffer;
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
 * operations so the exact command sequence can be checked. Covers the hash encoding round trip,
 * the atomic save of offer and index, pruning of stale index entries, and the WATCH/MULTI/EXEC
 * compare-and-set including its retry on a discarded transaction (Requirements 8.5-8.7).
 */
@SuppressWarnings({"unchecked", "rawtypes"})
class RedisJobOfferStoreTest {

    private static final Instant T0 = Instant.parse("2026-10-02T10:00:00Z");
    private static final Duration WINDOW = Duration.ofSeconds(60);

    private StringRedisTemplate redis;
    private RedisOperations<String, String> session;
    private HashOperations<String, Object, Object> hash;
    private SetOperations<String, String> set;
    private RedisJobOfferStore store;

    private final UUID bookingId = UUID.randomUUID();
    private final UUID provider = UUID.randomUUID();

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

    private JobOffer pending() {
        return JobOffer.pending(bookingId, provider, T0, WINDOW);
    }

    private JobOffer full() {
        return new JobOffer(bookingId, provider, OfferStatus.ACCEPTED, T0, T0.plus(WINDOW),
                T0.plusSeconds(12), true, UUID.randomUUID(), "HFX-2026-0000321",
                Instant.parse("2026-10-03T08:00:00Z"));
    }

    private static Map<Object, Object> stored(JobOffer offer) {
        return new HashMap<>(RedisJobOfferStore.toHash(offer));
    }

    // ---- encoding ------------------------------------------------------------------------------

    @Test
    void hashEncoding_roundTripsEveryField() {
        JobOffer offer = full();

        assertThat(RedisJobOfferStore.fromHash(stored(offer))).isEqualTo(offer);
    }

    @Test
    void hashEncoding_omitsAbsentOptionalFields() {
        Map<String, String> fields = RedisJobOfferStore.toHash(pending());

        assertThat(fields).doesNotContainKeys("decidedAt", "subcategoryId", "reference", "scheduledAt");
        assertThat(RedisJobOfferStore.fromHash(new HashMap<>(fields))).isEqualTo(pending());
    }

    // ---- save ----------------------------------------------------------------------------------

    @Test
    void save_replacesTheOfferAndIndexesItAtomicallyWithTheTtl() {
        Duration ttl = Duration.ofSeconds(90);
        String offerKey = RedisJobOfferStore.offerKey(bookingId);
        String indexKey = RedisJobOfferStore.indexKey(provider);

        store.save(pending(), ttl);

        InOrder order = inOrder(session, hash, set);
        order.verify(session).multi();
        order.verify(session).delete(offerKey);
        order.verify(hash).putAll(offerKey, RedisJobOfferStore.toHash(pending()));
        order.verify(session).expire(offerKey, ttl);
        order.verify(set).add(indexKey, bookingId.toString());
        order.verify(session).expire(indexKey, ttl);
        order.verify(session).exec();
        assertThat(offerKey).isEqualTo("dispatch:offer:booking:" + bookingId);
        assertThat(indexKey).isEqualTo("dispatch:offers:provider:" + provider);
    }

    // ---- reads ---------------------------------------------------------------------------------

    @Test
    void find_decodesTheStoredHash() {
        JobOffer offer = full();
        when(hash.entries(RedisJobOfferStore.offerKey(bookingId))).thenReturn(stored(offer));

        assertThat(store.find(bookingId)).contains(offer);
    }

    @Test
    void find_isEmptyWhenNothingIsStored() {
        when(hash.entries(anyString())).thenReturn(Map.of());

        assertThat(store.find(bookingId)).isEmpty();
    }

    @Test
    void findByProvider_returnsLiveOffersAndPrunesStaleIndexEntries() {
        UUID reoffered = UUID.randomUUID();
        UUID expired = UUID.randomUUID();
        String indexKey = RedisJobOfferStore.indexKey(provider);
        Set<String> members = new LinkedHashSet<>(List.of(bookingId.toString(), reoffered.toString(),
                expired.toString(), "not-a-uuid"));
        when(set.members(indexKey)).thenReturn(members);
        when(hash.entries(RedisJobOfferStore.offerKey(bookingId))).thenReturn(stored(pending()));
        when(hash.entries(RedisJobOfferStore.offerKey(reoffered))).thenReturn(
                stored(JobOffer.pending(reoffered, UUID.randomUUID(), T0, WINDOW)));
        when(hash.entries(RedisJobOfferStore.offerKey(expired))).thenReturn(Map.of());

        List<JobOffer> offers = store.findByProvider(provider);

        assertThat(offers).containsExactly(pending());
        verify(set).remove(indexKey, reoffered.toString());
        verify(set).remove(indexKey, expired.toString());
        verify(set).remove(indexKey, "not-a-uuid");
        verify(set, never()).remove(indexKey, bookingId.toString());
    }

    @Test
    void findByProvider_isEmptyWithoutAnIndex() {
        when(set.members(anyString())).thenReturn(null);

        assertThat(store.findByProvider(provider)).isEmpty();
    }

    // ---- compare-and-set -----------------------------------------------------------------------

    @Test
    void update_writesTheTransitionUnderWatchAndMulti() {
        String key = RedisJobOfferStore.offerKey(bookingId);
        when(hash.entries(key)).thenReturn(stored(pending()));
        // What a real commit returns: HMSET's status reply is dropped by RedisTemplate.exec(), so
        // only the HLEN count survives.
        when(session.exec()).thenReturn(List.of(9L));
        JobOffer accepted = pending().decide(provider, OfferStatus.ACCEPTED, T0.plusSeconds(5));

        Optional<Change> change = store.update(bookingId, o -> accepted);

        assertThat(change).contains(new Change(pending(), accepted));
        InOrder order = inOrder(session, hash);
        order.verify(session).watch(key);
        order.verify(hash).entries(key);
        order.verify(session).multi();
        order.verify(hash).putAll(key, RedisJobOfferStore.toHash(accepted));
        order.verify(hash).size(key);
        order.verify(session).exec();
        verify(session, times(1)).exec();
    }

    @Test
    void update_ofAMissingOffer_unwatchesAndReportsNothing() {
        when(hash.entries(anyString())).thenReturn(Map.of());

        assertThat(store.update(bookingId, o -> o)).isEmpty();
        verify(session).unwatch();
        verify(session, never()).multi();
    }

    @Test
    void update_thatChangesNothing_writesNothing() {
        when(hash.entries(anyString())).thenReturn(stored(pending()));

        Optional<Change> change = store.update(bookingId, o -> o);

        assertThat(change).contains(new Change(pending(), pending()));
        verify(session).unwatch();
        verify(session, never()).multi();
        verify(hash, never()).putAll(anyString(), anyMap());
    }

    @Test
    void update_retriesWhenAConcurrentWriteDiscardsTheTransaction() {
        String key = RedisJobOfferStore.offerKey(bookingId);
        JobOffer declinedMeanwhile = pending().decide(provider, OfferStatus.DECLINED, T0.plusSeconds(1));
        // First read sees PENDING but EXEC is discarded; the retry reads the concurrent DECLINED,
        // and the accept transition leaves it as it is.
        when(hash.entries(key)).thenReturn(stored(pending()), stored(declinedMeanwhile));
        when(session.exec()).thenReturn(null);

        Optional<Change> change = store.update(bookingId,
                o -> o.decide(provider, OfferStatus.ACCEPTED, T0.plusSeconds(2)));

        assertThat(change).contains(new Change(declinedMeanwhile, declinedMeanwhile));
        verify(session, times(2)).watch(key);
        verify(session, times(1)).exec();
    }

    @Test
    void update_treatsAnEmptyExecReplyAsAConflictToo() {
        when(hash.entries(anyString())).thenReturn(stored(pending()));
        when(session.exec()).thenReturn(List.of(), List.of(Boolean.TRUE));

        Optional<Change> change = store.update(bookingId,
                o -> o.close(provider, OfferStatus.EXPIRED, T0.plus(WINDOW)));

        assertThat(change).get().extracting(c -> c.after().status()).isEqualTo(OfferStatus.EXPIRED);
        verify(session, times(2)).exec();
    }

    @Test
    void update_givesUpAfterTheAttemptLimitUnderContention() {
        when(hash.entries(anyString())).thenReturn(stored(pending()));
        when(session.exec()).thenReturn(List.of());

        assertThatThrownBy(() -> store.update(bookingId,
                o -> o.close(null, OfferStatus.WITHDRAWN, T0)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(bookingId.toString());
        verify(session, times(RedisJobOfferStore.MAX_CAS_ATTEMPTS)).exec();
        verify(hash, times(RedisJobOfferStore.MAX_CAS_ATTEMPTS)).putAll(eq(RedisJobOfferStore.offerKey(bookingId)), anyMap());
    }
}
