package com.homefix.booking.service;

import java.util.UUID;

import com.homefix.booking.domain.BookingStatus;

/**
 * Thrown when a booking state transition is not permitted by the {@code BookingStateMachine}
 * (Requirement 9.2, Property 8). The REST layer maps this to HTTP 409 Conflict; the service
 * logs the rejected attempt with the booking id, source state, target state, and actor before
 * throwing.
 */
public class InvalidTransitionException extends RuntimeException {

    private final UUID bookingId;
    private final BookingStatus fromState;
    private final BookingStatus toState;

    public InvalidTransitionException(UUID bookingId, BookingStatus fromState, BookingStatus toState) {
        super("Illegal booking transition " + fromState + " -> " + toState + " for booking " + bookingId);
        this.bookingId = bookingId;
        this.fromState = fromState;
        this.toState = toState;
    }

    public UUID getBookingId() {
        return bookingId;
    }

    public BookingStatus getFromState() {
        return fromState;
    }

    public BookingStatus getToState() {
        return toState;
    }
}
