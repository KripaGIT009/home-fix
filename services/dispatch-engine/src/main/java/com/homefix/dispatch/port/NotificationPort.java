package com.homefix.dispatch.port;

import java.time.Instant;
import java.util.UUID;

/**
 * Outbound port for the dispatch signalling that goes to people rather than services: the
 * dispatcher-team alert when all radius cycles are exhausted (Requirement 8.9) and the push that
 * tells a provider a job offer is waiting (Requirement 8.5). The concrete transport is hidden so
 * these paths are unit-testable.
 *
 * <p>The customer's "no provider available" notice is deliberately not here. The Booking Service
 * publishes {@code BookingCancelled} with {@code status = SEARCHING_FAILED} in the same transaction
 * that fails the booking, and the Notification Service turns that event into the customer's notice
 * on every channel. A separate call from the Dispatch Engine duplicated it — or would have, had the
 * endpoint it called ever existed.
 *
 * <p>Every method is best-effort: implementations must log and swallow delivery failures, never
 * throw, because none of these notices may break or stall dispatch.
 */
public interface NotificationPort {

    /** Alerts the dispatcher team via the internal channel that a booking search failed. */
    void alertDispatcherTeam(UUID bookingId);

    /**
     * Tells a provider a job offer is waiting for them. The offer itself is already recorded and
     * visible through {@code GET /dispatch/offers}; this push only gets the provider to look.
     *
     * @param bookingId  the booking on offer
     * @param providerId the provider it was offered to
     * @param expiresAt  when the response window closes
     */
    void notifyProviderOfJobOffer(UUID bookingId, UUID providerId, Instant expiresAt);
}
