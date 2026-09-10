package com.homefix.dispatch.port;

import java.util.UUID;

/**
 * Outbound port to the Booking Service for the two terminal dispatch transitions
 * (Requirements 8.6, 8.9). The Booking Service owns the booking state machine; the Dispatch
 * Engine only requests transitions through it, never mutates booking rows directly.
 *
 * <ul>
 *   <li>{@link #markProviderAccepted} — a provider accepted; move SEARCHING_PROVIDER →
 *       PROVIDER_ACCEPTED and record the assigned provider (Requirement 8.6).</li>
 *   <li>{@link #markSearchingFailed} — every candidate across every radius cycle declined or timed
 *       out; move the booking to SEARCHING_FAILED (Requirement 8.9).</li>
 * </ul>
 */
public interface BookingTransitionPort {

    /** Requests the SEARCHING_PROVIDER → PROVIDER_ACCEPTED transition, recording the provider. */
    void markProviderAccepted(UUID bookingId, UUID providerId);

    /** Requests the transition to SEARCHING_FAILED after all cycles are exhausted. */
    void markSearchingFailed(UUID bookingId);
}
