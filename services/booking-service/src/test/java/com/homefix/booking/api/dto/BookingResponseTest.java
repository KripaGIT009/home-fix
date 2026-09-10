package com.homefix.booking.api.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.homefix.booking.domain.Booking;
import com.homefix.booking.domain.BookingStatus;
import com.homefix.booking.pricing.PriceEstimate;

/**
 * Unit tests for {@link BookingResponse} mapping (Requirement 7.3): {@code of} produces a
 * response without an estimate; {@code withEstimate} attaches the itemized estimate view.
 */
class BookingResponseTest {

    private static Booking booking() {
        Booking b = Booking.create("HFX-9", UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), false, null, new BigDecimal("100.00"));
        b.applyStatus(BookingStatus.SEARCHING_PROVIDER);
        b.setCancellationFee(new BigDecimal("5.00"));
        return b;
    }

    @Test
    void ofMapsBookingWithoutEstimate() {
        Booking b = booking();

        BookingResponse resp = BookingResponse.of(b);

        assertThat(resp.bookingId()).isEqualTo(b.getId());
        assertThat(resp.reference()).isEqualTo("HFX-9");
        assertThat(resp.status()).isEqualTo("SEARCHING_PROVIDER");
        assertThat(resp.emergency()).isFalse();
        assertThat(resp.estimatedTotal()).isEqualByComparingTo("100.00");
        assertThat(resp.cancellationFee()).isEqualByComparingTo("5.00");
        assertThat(resp.estimate()).isNull();
    }

    @Test
    void withEstimateAttachesItemizedView() {
        Booking b = booking();
        PriceEstimate estimate = new PriceEstimate(new BigDecimal("120.00"),
                Map.of("basePrice", new BigDecimal("100.00"), "taxes", new BigDecimal("20.00")));

        BookingResponse resp = BookingResponse.withEstimate(b, estimate);

        assertThat(resp.estimate()).isNotNull();
        assertThat(resp.estimate().total()).isEqualByComparingTo("120.00");
        assertThat(resp.estimate().components()).containsEntry("basePrice", new BigDecimal("100.00"));
    }

    @Test
    void withNullEstimateYieldsNullView() {
        BookingResponse resp = BookingResponse.withEstimate(booking(), null);

        assertThat(resp.estimate()).isNull();
    }
}
