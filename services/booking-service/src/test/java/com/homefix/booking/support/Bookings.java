package com.homefix.booking.support;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.homefix.booking.domain.Booking;
import com.homefix.booking.domain.BookingStatus;

/**
 * Test factory for {@link Booking} instances in an arbitrary starting state.
 */
public final class Bookings {

    private Bookings() {
    }

    public static Booking create() {
        return Booking.create("HFX-20240101-ABC123", UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), false, Instant.now(), new BigDecimal("100.00"));
    }

    /** A booking forced into {@code status} for transition/cancellation setup. */
    public static Booking inState(BookingStatus status) {
        Booking b = create();
        b.applyStatus(status);
        return b;
    }
}
