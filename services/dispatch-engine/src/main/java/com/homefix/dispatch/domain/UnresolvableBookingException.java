package com.homefix.dispatch.domain;

/**
 * A booking whose matching problem cannot be completed however often it is retried: its address
 * does not exist, belongs to a different customer, or was never supplied; its subcategory is not
 * in the active catalog; or the catalog configures no skill tags for it (Requirement 8.2).
 *
 * <p>Dispatching on a guess would be worse than not dispatching: an absent coordinate used to
 * become latitude 0, longitude 0, and empty skill tags would match any provider. The
 * {@code BookingCreated} consumer catches this, logs the message (which names the booking and the
 * identifier that needs fixing), and moves the booking to SEARCHING_FAILED rather than leaving it
 * searching forever.
 */
public class UnresolvableBookingException extends RuntimeException {

    public UnresolvableBookingException(String message) {
        super(message);
    }
}
