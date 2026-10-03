package com.homefix.dispatch.port;

import com.homefix.dispatch.domain.SearchingFailedOutcome;

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
 *       out; move the booking to SEARCHING_FAILED (Requirement 8.9). The Booking Service may instead
 *       route the booking to the partner agencies covering it (AWAITING_ASSIGNMENT, Requirement
 *       MT-4.2), so the call reports which of the two happened.</li>
 * </ul>
 */
public interface BookingTransitionPort {

    /** Requests the SEARCHING_PROVIDER → PROVIDER_ACCEPTED transition, recording the provider. */
    void markProviderAccepted(UUID bookingId, UUID providerId);

    /**
     * Reports that all cycles are exhausted and asks the Booking Service to end the search.
     *
     * @return whether the booking was failed or handed to Tenants for assignment, so the caller
     *         sends the "no provider available" notices only for a booking that was really failed
     */
    SearchingFailedOutcome markSearchingFailed(UUID bookingId);
}
