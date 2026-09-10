package com.homefix.location.store;

import java.util.Optional;
import java.util.UUID;

import com.homefix.location.domain.CachedLocation;

/**
 * Abstraction over the low-latency last-known-location cache (Redis in production, keyed by
 * {@code location:{bookingId}:{providerId}} — see design). Kept behind an interface so the
 * core {@code LocationService} can be unit-tested against an in-memory fake and so the
 * caching technology can be swapped without touching business logic (Requirement 10.2, 10.3).
 */
public interface LocationStorePort {

    /**
     * Stores (or overwrites) the last known location for a Booking.
     */
    void save(CachedLocation location);

    /**
     * Returns the most recently stored location for a Booking, or empty if none is cached.
     */
    Optional<CachedLocation> findLatest(UUID bookingId);

    /**
     * Removes any cached location for a Booking. Invoked when a Booking transitions to
     * JOB_STARTED and updates are no longer accepted (Requirement 10.5).
     */
    void evict(UUID bookingId);
}
