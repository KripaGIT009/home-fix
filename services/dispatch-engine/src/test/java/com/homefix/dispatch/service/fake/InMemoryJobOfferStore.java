package com.homefix.dispatch.service.fake;

import com.homefix.dispatch.domain.JobOffer;
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
 * In-memory {@link JobOfferStore} with the same contract as the Redis one: one offer per booking,
 * a per-provider index that may go stale, and {@link #update} applied atomically (here by
 * synchronising). TTLs are recorded, not enforced.
 */
public class InMemoryJobOfferStore implements JobOfferStore {

    private final Map<UUID, JobOffer> offers = new LinkedHashMap<>();
    private final Map<UUID, Set<UUID>> index = new HashMap<>();
    private final Map<UUID, Duration> ttls = new HashMap<>();

    @Override
    public synchronized void save(JobOffer offer, Duration ttl) {
        offers.put(offer.bookingId(), offer);
        index.computeIfAbsent(offer.providerId(), p -> new LinkedHashSet<>()).add(offer.bookingId());
        ttls.put(offer.bookingId(), ttl);
    }

    @Override
    public synchronized Optional<JobOffer> find(UUID bookingId) {
        return Optional.ofNullable(offers.get(bookingId));
    }

    @Override
    public synchronized List<JobOffer> findByProvider(UUID providerId) {
        return index.getOrDefault(providerId, Set.of()).stream()
                .map(offers::get)
                .filter(o -> o != null)
                .toList();
    }

    @Override
    public synchronized Optional<Change> update(UUID bookingId, UnaryOperator<JobOffer> transition) {
        JobOffer before = offers.get(bookingId);
        if (before == null) {
            return Optional.empty();
        }
        JobOffer after = transition.apply(before);
        offers.put(bookingId, after);
        return Optional.of(new Change(before, after));
    }

    public synchronized Duration ttlOf(UUID bookingId) {
        return ttls.get(bookingId);
    }

    /** Simulates the offer's key expiring out of storage. */
    public synchronized void evict(UUID bookingId) {
        offers.remove(bookingId);
    }
}
