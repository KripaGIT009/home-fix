package com.homefix.dispatch.port;

import java.util.UUID;

/**
 * Outbound port for the "no provider available" signalling required when all radius cycles are
 * exhausted (Requirement 8.9): notify the customer via push and SMS, and alert the dispatcher
 * team via an internal channel. The concrete transport (Notification Service call, internal
 * alert bus) is hidden so the exhaustion path is unit-testable.
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
}
