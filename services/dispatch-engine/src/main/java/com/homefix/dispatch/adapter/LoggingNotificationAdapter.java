package com.homefix.dispatch.adapter;

import com.homefix.dispatch.port.NotificationPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Default {@link NotificationPort}: records the dispatcher alert and the job-offer nudge in the log
 * instead of calling the Notification Service.
 *
 * <p>This replaced an HTTP adapter that posted to {@code /internal/notifications/no-provider-available},
 * {@code /dispatcher-alert} and {@code /job-offer}. The Notification Service has never had those
 * endpoints — it is driven by Kafka events (docs/ARCHITECTURE.md) and exposes only its admin
 * template API — so every call answered 404 and was logged as a failed dependency, once per offer
 * and twice per failed search. Of the three:
 * <ul>
 *   <li>the customer's "no provider available" notice was redundant and is gone: the Booking
 *       Service's {@code BookingCancelled} ({@code status = SEARCHING_FAILED}) already produces it;</li>
 *   <li>the dispatcher alert has no counterpart (the Notification Service has no staff audience), so
 *       it is written here at WARN under a fixed {@value #DISPATCHER_ALERT} marker that log-based
 *       alerting can match; the failed booking is also listed in the Admin Portal's Bookings module;</li>
 *   <li>the job-offer push has no counterpart either. Providers see offers by polling
 *       {@code GET /dispatch/offers}, which is what the provider app does, so dispatch loses nothing.
 *       A real push needs a Notification Service contract — an offer event with a provider template,
 *       or an internal endpoint — which is a design change, not a fix.</li>
 * </ul>
 */
@Component
public class LoggingNotificationAdapter implements NotificationPort {

    private static final Logger log = LoggerFactory.getLogger(LoggingNotificationAdapter.class);

    /** Fixed token at the start of every dispatcher alert, for log-based alerting. */
    static final String DISPATCHER_ALERT = "DISPATCHER_ALERT";

    @Override
    public void alertDispatcherTeam(UUID bookingId) {
        log.warn("{} booking {} reached SEARCHING_FAILED: no provider accepted in any radius cycle",
                DISPATCHER_ALERT, bookingId);
    }

    @Override
    public void notifyProviderOfJobOffer(UUID bookingId, UUID providerId, Instant expiresAt) {
        log.debug("Job offer for booking {} awaits provider {} until {} (no push channel; the provider"
                + " app polls for offers)", bookingId, providerId, expiresAt);
    }
}
