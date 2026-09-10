package com.homefix.customer.support;

import java.util.Optional;
import java.util.UUID;

import com.homefix.customer.booking.BookingClientPort;

/**
 * Configurable {@link BookingClientPort} test double. By default reports no active booking
 * uses the address; {@link #markInUse(String)} makes every lookup return a blocking
 * booking reference.
 */
public class FakeBookingClientPort implements BookingClientPort {

    private String activeBookingReference = null;

    public void markInUse(String bookingReference) {
        this.activeBookingReference = bookingReference;
    }

    public void clear() {
        this.activeBookingReference = null;
    }

    @Override
    public Optional<String> findActiveBookingUsingAddress(UUID customerId, UUID addressId) {
        return Optional.ofNullable(activeBookingReference);
    }
}
