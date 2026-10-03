package com.homefix.dispatch.domain;

/**
 * What the Booking Service did with a booking the Dispatch Engine could not place. Since the
 * multi-tenant fallback, "searching failed" is a request rather than a verdict: the Booking
 * Service either fails the booking as before or hands it to the partner agencies (Tenants) covering
 * it (Requirements MT-4.2, MT-4.3). Only a booking that was actually failed may get the "no provider
 * available" notices; a routed one is still being served.
 */
public enum SearchingFailedOutcome {

    /** The booking is SEARCHING_FAILED; the customer and the dispatcher team are told (Requirement 8.9). */
    SEARCHING_FAILED,

    /** The booking was queued for a Tenant to assign a provider (AWAITING_ASSIGNMENT, Requirement MT-4.2). */
    AWAITING_ASSIGNMENT;

    /**
     * Maps the booking status the Booking Service answered with. Anything other than
     * {@code AWAITING_ASSIGNMENT} — including a missing status from a Booking Service that predates
     * Tenants — means the booking was failed, so the notices keep being sent exactly as before.
     */
    public static SearchingFailedOutcome fromBookingStatus(String status) {
        return AWAITING_ASSIGNMENT.name().equals(status) ? AWAITING_ASSIGNMENT : SEARCHING_FAILED;
    }
}
