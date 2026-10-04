package com.homefix.dispatch.service.fake;

import com.homefix.dispatch.adapter.HttpBookingTransitionAdapter.BookingTransitionException;
import com.homefix.dispatch.domain.BookingNotSearchableException;
import com.homefix.dispatch.domain.SearchingFailedOutcome;
import com.homefix.dispatch.port.BookingTransitionPort;

import java.util.UUID;

/**
 * Records which terminal transition the dispatch loop requested (Requirements 8.6, 8.9).
 * {@link #refuseAsNotSearchable()} makes both transitions answer as the Booking Service does for a
 * booking that has been cancelled (409). {@link #routeToTenants()} makes the SEARCHING_FAILED request
 * answer as the Booking Service does when partner agencies cover the booking (AWAITING_ASSIGNMENT,
 * Requirement MT-4.2). {@link #unavailableFor(int)} makes the acceptance fail as the adapter does
 * when the Booking Service cannot be reached.
 */
public class RecordingBookingTransition implements BookingTransitionPort {

    private UUID acceptedBookingId;
    private UUID acceptedProviderId;
    private UUID searchingFailedBookingId;
    private boolean notSearchable;
    private int unavailableAcceptances;
    private int acceptCalls;
    private SearchingFailedOutcome searchingFailedOutcome = SearchingFailedOutcome.SEARCHING_FAILED;

    public RecordingBookingTransition refuseAsNotSearchable() {
        this.notSearchable = true;
        return this;
    }

    public RecordingBookingTransition routeToTenants() {
        this.searchingFailedOutcome = SearchingFailedOutcome.AWAITING_ASSIGNMENT;
        return this;
    }

    /** Makes the next {@code calls} acceptance requests fail as a Booking Service outage would. */
    public RecordingBookingTransition unavailableFor(int calls) {
        this.unavailableAcceptances = calls;
        return this;
    }

    /** Whether acceptance requests are refused with 409 from now on. */
    public RecordingBookingTransition refuseAsNotSearchable(boolean refuse) {
        this.notSearchable = refuse;
        return this;
    }

    @Override
    public void markProviderAccepted(UUID bookingId, UUID providerId) {
        acceptCalls++;
        if (unavailableAcceptances > 0) {
            unavailableAcceptances--;
            throw new BookingTransitionException("Booking Service transition unavailable (degraded)", null);
        }
        if (notSearchable) {
            throw new BookingNotSearchableException(bookingId, "409 from the Booking Service", null);
        }
        this.acceptedBookingId = bookingId;
        this.acceptedProviderId = providerId;
    }

    @Override
    public SearchingFailedOutcome markSearchingFailed(UUID bookingId) {
        if (notSearchable) {
            throw new BookingNotSearchableException(bookingId, "409 from the Booking Service", null);
        }
        this.searchingFailedBookingId = bookingId;
        return searchingFailedOutcome;
    }

    /** Acceptance requests made, failed ones included. */
    public int acceptCalls() {
        return acceptCalls;
    }

    public boolean acceptedCalled() {
        return acceptedBookingId != null;
    }

    public UUID acceptedProviderId() {
        return acceptedProviderId;
    }

    public boolean searchingFailedCalled() {
        return searchingFailedBookingId != null;
    }
}
