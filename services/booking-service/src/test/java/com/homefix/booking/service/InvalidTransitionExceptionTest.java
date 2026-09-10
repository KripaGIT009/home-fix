package com.homefix.booking.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.homefix.booking.domain.BookingStatus;

/**
 * Unit tests for {@link InvalidTransitionException}: it carries the booking id, source, and
 * target states so the REST layer can build the 409 error envelope (Requirement 9.2, Property 8).
 */
class InvalidTransitionExceptionTest {

    @Test
    void carriesBookingIdAndStatesAndDescriptiveMessage() {
        UUID booking = UUID.randomUUID();
        InvalidTransitionException ex = new InvalidTransitionException(
                booking, BookingStatus.CREATED, BookingStatus.JOB_COMPLETED);

        assertThat(ex.getBookingId()).isEqualTo(booking);
        assertThat(ex.getFromState()).isEqualTo(BookingStatus.CREATED);
        assertThat(ex.getToState()).isEqualTo(BookingStatus.JOB_COMPLETED);
        assertThat(ex.getMessage()).contains("CREATED", "JOB_COMPLETED", booking.toString());
    }
}
