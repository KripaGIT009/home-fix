package com.homefix.dispatch.port;

import java.time.Duration;
import java.util.UUID;

/**
 * Outbound port that sends a job offer to a provider and awaits the provider's response
 * (Requirements 8.5-8.7). Sending an offer and blocking for acceptance is a transport concern
 * (push notification + provider-app callback, or a synchronous RPC in tests), so it is hidden
 * behind this port and mocked in unit tests.
 */
public interface JobOfferPort {

    /**
     * Sends a job offer for {@code bookingId} to {@code providerId} and waits up to {@code timeout}
     * for a response.
     *
     * @return the provider's decision: {@link OfferOutcome#ACCEPTED},
     *         {@link OfferOutcome#REJECTED}, or {@link OfferOutcome#TIMED_OUT}
     */
    OfferOutcome offer(UUID bookingId, UUID providerId, Duration timeout);

    /** The outcome of a single job offer. */
    enum OfferOutcome {
        ACCEPTED,
        REJECTED,
        TIMED_OUT
    }
}
