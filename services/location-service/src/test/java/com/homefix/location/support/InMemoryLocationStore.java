package com.homefix.location.support;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.homefix.location.domain.CachedLocation;
import com.homefix.location.store.LocationStorePort;

/**
 * Simple in-memory {@link LocationStorePort} for unit tests — no Redis required. Keeps only the
 * most recently saved location per booking, matching the cache's last-known-location semantics.
 */
public class InMemoryLocationStore implements LocationStorePort {

    private final Map<UUID, CachedLocation> latestByBooking = new HashMap<>();

    @Override
    public void save(CachedLocation location) {
        latestByBooking.put(location.bookingId(), location);
    }

    @Override
    public Optional<CachedLocation> findLatest(UUID bookingId) {
        return Optional.ofNullable(latestByBooking.get(bookingId));
    }

    @Override
    public void evict(UUID bookingId) {
        latestByBooking.remove(bookingId);
    }
}
