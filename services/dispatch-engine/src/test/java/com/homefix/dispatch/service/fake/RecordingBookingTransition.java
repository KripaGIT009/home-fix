package com.homefix.dispatch.service.fake;

import com.homefix.dispatch.domain.BookingNotSearchableException;
import com.homefix.dispatch.domain.SearchingFailedOutcome;
import com.homefix.dispatch.port.BookingTransitionPort;

import java.util.UUID;

/**
 * Records which terminal transition the dispatch loop requested (Requirements 8.6, 8.9).
 * {@link #refuseAsNotSearchable()} makes both transitions answer as the Booking Service does for a
 * booking that has been cancelled (409). {@link #routeToTenants()} makes the SEARCHING_FAILED request
 * answer as the Booking Service does when partner agencies cover the booking (AWAITING_ASSIGNMENT,
 * Requirement MT-4.2).
 */
public class RecordingBookingTransition implements BookingTransitionPort {

    private UUID acceptedBookingId;
    private UUID acceptedProviderId;
    private UUID searchingFailedBookingId;
    private boolean notSearchable;
    private SearchingFailedOutcome searchingFailedOutcome = SearchingFailedOutcome.SEARCHING_FAILED;

    public RecordingBookingTransition refuseAsNotSearchable() {
        this.notSearchable = true;
        return this;
    }

    public RecordingBookingTransition routeToTenants() {
        this.searchingFailedOutcome = SearchingFailedOutcome.AWAITING_ASSIGNMENT;
        return this;
    }

    @Override
    public void markProviderAccepted(UUID bookingId, UUID providerId) {
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
