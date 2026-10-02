package com.homefix.booking.support;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.springframework.test.util.ReflectionTestUtils;

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

    /**
     * A booking placed by {@code customerId} for {@code subcategoryId} at a fixed
     * {@code createdAt}, for read-side ordering tests. {@code Booking} stamps its creation time
     * itself and exposes no setter, so the field is overwritten after construction; two bookings
     * created back to back could otherwise share an instant and have no defined order.
     */
    public static Booking placed(UUID customerId, UUID subcategoryId, String reference,
                                 Instant createdAt, Instant scheduledAt) {
        Booking b = Booking.create(reference, customerId, UUID.randomUUID(), subcategoryId,
                UUID.randomUUID(), false, scheduledAt, new BigDecimal("100.00"));
        ReflectionTestUtils.setField(b, "createdAt", createdAt);
        return b;
    }
}
