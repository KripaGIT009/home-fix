package com.homefix.dispatch.port;

import java.time.Instant;
import java.util.UUID;

/**
 * Outbound port for the dispatch signalling that goes to people rather than services: the
 * "no provider available" notices required when all radius cycles are exhausted (Requirement 8.9)
 * — the customer via push and SMS, the dispatcher team via an internal channel — and the push that
 * tells a provider a job offer is waiting (Requirement 8.5). The concrete transport (Notification
 * Service call, internal alert bus) is hidden so these paths are unit-testable.
 *
 * <p>Every method is best-effort: implementations must log and swallow delivery failures, never
 * throw, because none of these notices may break or stall dispatch.
 */
public interface NotificationPort {

    /**
     * Notifies the customer of a failed search via push notification and SMS.
     *
     * @param bookingId  the booking that failed to match
     * @param customerId the customer to notify
     */
    void notifyCustomerNoProviderAvailable(UUID bookingId, UUID customerId);

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
