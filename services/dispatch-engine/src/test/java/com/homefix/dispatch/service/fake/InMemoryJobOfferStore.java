package com.homefix.dispatch.service.fake;

import com.homefix.dispatch.domain.JobOffer;
import com.homefix.dispatch.domain.OfferRound;
import com.homefix.dispatch.port.JobOfferStore;

import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * In-memory {@link JobOfferStore} with the same contract as the Redis one: one {@link OfferRound}
 * per booking, a per-provider index that may go stale, and {@link #update} applied atomically
 * (here by synchronising). TTLs are recorded, not enforced.
 */
public class InMemoryJobOfferStore implements JobOfferStore {

    private final Map<UUID, OfferRound> rounds = new LinkedHashMap<>();
    private final Map<UUID, Set<UUID>> index = new HashMap<>();
    private final Map<UUID, Duration> ttls = new HashMap<>();

    @Override
    public synchronized Optional<OfferRound> find(UUID bookingId) {
        return Optional.ofNullable(rounds.get(bookingId));
    }

    @Override
    public synchronized List<JobOffer> findByProvider(UUID providerId) {
        return index.getOrDefault(providerId, Set.of()).stream()
                .map(rounds::get)
                .filter(r -> r != null)
                .flatMap(r -> r.offerTo(providerId).stream())
                .toList();
    }

    @Override
    public synchronized Change update(UUID bookingId, UnaryOperator<OfferRound> transition, Duration ttl) {
        OfferRound before = rounds.getOrDefault(bookingId, OfferRound.empty(bookingId));
        OfferRound after = transition.apply(before);
        if (after.equals(before)) {
            return new Change(before, before);
        }
        rounds.put(bookingId, after);
        for (JobOffer offer : after.offers()) {
            index.computeIfAbsent(offer.providerId(), p -> new LinkedHashSet<>()).add(bookingId);
        }
        if (ttl != null) {
            ttls.put(bookingId, ttl);
        }
        return new Change(before, after);
    }

    /** Stores a single offer directly, as if a one-provider wave had been opened. */
    public synchronized void put(JobOffer offer, Duration ttl) {
        update(offer.bookingId(), r -> r.open(List.of(offer)), ttl);
    }

    public synchronized Duration ttlOf(UUID bookingId) {
        return ttls.get(bookingId);
    }

    /** Simulates the round's key expiring out of storage. */
    public synchronized void evict(UUID bookingId) {
        rounds.remove(bookingId);
    }
}
