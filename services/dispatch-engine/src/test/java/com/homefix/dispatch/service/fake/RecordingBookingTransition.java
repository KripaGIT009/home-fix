package com.homefix.dispatch.service.fake;

import com.homefix.dispatch.port.BookingTransitionPort;

import java.util.UUID;

/** Records which terminal transition the dispatch loop requested (Requirements 8.6, 8.9). */
public class RecordingBookingTransition implements BookingTransitionPort {

    private UUID acceptedBookingId;
    private UUID acceptedProviderId;
    private UUID searchingFailedBookingId;

    @Override
    public void markProviderAccepted(UUID bookingId, UUID providerId) {
        this.acceptedBookingId = bookingId;
        this.acceptedProviderId = providerId;
    }

    @Override
    public void markSearchingFailed(UUID bookingId) {
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
