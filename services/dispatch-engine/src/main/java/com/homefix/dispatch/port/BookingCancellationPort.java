package com.homefix.dispatch.port;

import java.util.UUID;

/**
 * Remembers which bookings have left the search (cancelled, or ended by SEARCHING_FAILED) so an
 * in-flight dispatch can stop before offering the job to anyone else (Requirement 8.7).
 *
 * <p>Fed by the {@code BookingCancelled} consumer and read by the dispatch loop before every offer.
 * The Booking Service has no internal status endpoint to ask instead, and polling it per offer would
 * put a synchronous call on the dispatch path; a flag in shared storage is one cheap read.
 */
public interface BookingCancellationPort {

    /** Records that {@code bookingId} no longer needs a provider. Idempotent. */
    void markCancelled(UUID bookingId);

    /**
     * @return true if {@code bookingId} was marked cancelled. Implementations answer {@code false}
     *         when they cannot tell, so a storage outage never stops a live search.
     */
    boolean isCancelled(UUID bookingId);
}
