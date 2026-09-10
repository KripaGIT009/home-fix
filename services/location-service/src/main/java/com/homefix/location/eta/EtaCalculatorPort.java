package com.homefix.location.eta;

import java.util.UUID;

import com.homefix.location.domain.Coordinates;

/**
 * Abstraction over the ETA / Maps calculation (Requirement 10.4). Given the Provider's current
 * coordinates and a Booking, returns the estimated time of arrival in minutes at the Customer's
 * service address.
 *
 * <p>Kept behind an interface so the core {@code LocationService} can be unit-tested with a
 * deterministic stub and so the underlying Maps provider can be swapped without touching
 * business logic.
 */
public interface EtaCalculatorPort {

    /**
     * Calculates the ETA in minutes from {@code providerLocation} to the Customer's service
     * address for the given Booking.
     *
     * @param bookingId        the active Booking whose Customer address is the destination
     * @param providerLocation the Provider's current coordinates
     * @return the estimated time of arrival in whole minutes (never negative)
     */
    int etaMinutes(UUID bookingId, Coordinates providerLocation);
}
