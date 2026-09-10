package com.homefix.customer.booking;

/**
 * Raised when the Booking Service cannot be reached to verify active-booking references
 * for an address. Because we cannot prove the address is unused, the deletion is failed
 * safely rather than allowed (Requirement 2.6).
 */
public class BookingLookupException extends RuntimeException {

    public BookingLookupException(String message, Throwable cause) {
        super(message, cause);
    }
}
