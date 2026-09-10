package com.homefix.customer.geocoding;

import java.util.Optional;

/**
 * Hexagonal port abstracting the outbound Maps / reverse-geocoding API (Requirement 2.2).
 *
 * <p>The address logic depends only on this interface, never on a concrete Maps SDK
 * (Google Maps, Mapbox, ...). Adding a new provider requires only a new adapter, and tests
 * can substitute a deterministic implementation — including one that simulates a
 * geocoding failure.
 */
public interface GeocodingPort {

    /**
     * Reverse-geocodes GPS coordinates into a human-readable street address.
     *
     * @param lat latitude
     * @param lng longitude
     * @return the resolved street address, or {@link Optional#empty()} if the coordinates
     *         could not be resolved. Callers treat empty as a geocoding failure and store
     *         the raw coordinates with a warning (Requirement 2.2).
     */
    Optional<String> reverseGeocode(double lat, double lng);
}
