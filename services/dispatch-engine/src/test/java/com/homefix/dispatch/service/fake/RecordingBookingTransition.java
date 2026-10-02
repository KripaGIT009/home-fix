package com.homefix.dispatch.service.fake;

import com.homefix.dispatch.domain.BookingNotSearchableException;
import com.homefix.dispatch.port.BookingTransitionPort;

import java.util.UUID;

/**
 * Records which terminal transition the dispatch loop requested (Requirements 8.6, 8.9).
 * {@link #refuseAsNotSearchable()} makes both transitions answer as the Booking Service does for a
 * booking that has been cancelled (409).
 */
public class RecordingBookingTransition implements BookingTransitionPort {

    private UUID acceptedBookingId;
    private UUID acceptedProviderId;
    private UUID searchingFailedBookingId;
    private boolean notSearchable;

    public RecordingBookingTransition refuseAsNotSearchable() {
        this.notSearchable = true;
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
    public void markSearchingFailed(UUID bookingId) {
        if (notSearchable) {
            throw new BookingNotSearchableException(bookingId, "409 from the Booking Service", null);
        }
        this.searchingFailedBookingId = bookingId;
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
