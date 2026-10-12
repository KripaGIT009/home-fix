package com.homefix.location.booking;

import java.util.Optional;
import java.util.UUID;

/**
 * The Location Service's view of the Booking Service: who a booking is between. Used to check
 * ownership before a provider's position is shown or recorded.
 *
 * <p>Implementations fail <em>closed</em>: when the Booking Service cannot answer they throw
 * {@code LocationException} {@code 503 LOCATION_BOOKING_LOOKUP_UNAVAILABLE} rather than returning
 * something a caller could be let through on.
 */
public interface BookingParticipantsPort {

    /**
     * @return the booking's participants, or empty when the Booking Service does not know it
     * @throws com.homefix.location.service.LocationException 503 when the Booking Service cannot be
     *                                                       asked
     */
    Optional<BookingParticipants> participants(UUID bookingId);
}
