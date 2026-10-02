package com.homefix.dispatch.domain;

import java.util.UUID;

/**
 * The booking no longer needs a provider: it was cancelled, or otherwise left SEARCHING_PROVIDER,
 * while dispatch was still working on it. The Booking Service reports this as a 409 (or a 404 for a
 * booking it does not know) when asked for a transition, and the Dispatch Engine learns of a
 * cancellation directly from {@code BookingCancelled}.
 *
 * <p>Not a failure: the dispatch loop stops quietly, without offering anyone else, publishing
 * further ProviderRejected events, or sending the "no provider available" notices.
 */
public class BookingNotSearchableException extends RuntimeException {

    private final UUID bookingId;

    public BookingNotSearchableException(UUID bookingId, String message, Throwable cause) {
        super(message, cause);
        this.bookingId = bookingId;
    }

    public UUID bookingId() {
        return bookingId;
    }
}
