package com.homefix.location.subscription;

import java.util.UUID;

/**
 * Abstraction over the per-Booking subscriber registry that manages WebSocket/SSE session
 * lifecycle and fan-out (Requirement 10.2, 10.5). Kept behind an interface so the core
 * {@code LocationService} can be unit-tested with a mock and so the transport (WebSocket vs
 * SSE) can be swapped without touching business logic.
 *
 * <p>Implementations must be safe for concurrent use: subscribers register and deregister on
 * their own connection threads while updates are pushed on ingestion threads.
 */
public interface SubscriberRegistryPort {

    /**
     * Registers a subscriber session against a Booking so it receives subsequent pushes.
     *
     * @param bookingId the Booking whose feed the session subscribes to
     * @param session   the transport-level session handle to deliver pushes to
     */
    void register(UUID bookingId, SubscriberSession session);

    /**
     * Deregisters a single subscriber session from a Booking (e.g. the client disconnected).
     */
    void deregister(UUID bookingId, SubscriberSession session);

    /**
     * Pushes an update to every subscriber currently registered for the Booking.
     */
    void push(UUID bookingId, LocationUpdatePush update);

    /**
     * Terminates and removes all subscriber sessions for a Booking. Invoked when the Booking
     * transitions to JOB_STARTED (Requirement 10.5).
     */
    void terminateAll(UUID bookingId);

    /**
     * Returns the number of active subscriber sessions for a Booking (primarily for metrics
     * and tests).
     */
    int subscriberCount(UUID bookingId);
}
