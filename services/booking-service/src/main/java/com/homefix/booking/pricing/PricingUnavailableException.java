package com.homefix.booking.pricing;

/**
 * Thrown by a {@link PricingClientPort} implementation when the Pricing Engine cannot be
 * reached or returns an error. The booking flow translates this into a 503 response and does
 * NOT create a booking (Requirement 7.4).
 */
public class PricingUnavailableException extends RuntimeException {

    public PricingUnavailableException(String message) {
        super(message);
    }

    public PricingUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
