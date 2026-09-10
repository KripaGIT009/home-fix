package com.homefix.location.eta;

import java.util.UUID;

import com.homefix.location.domain.Coordinates;

/**
 * Default {@link EtaCalculatorPort} used until a real Maps provider is wired in. It estimates
 * ETA from the straight-line (haversine) distance between the Provider's coordinates and the
 * Customer's service address divided by an assumed average travel speed, rounding up to whole
 * minutes (Requirement 10.4).
 *
 * <p>The Customer destination is resolved via an injected {@link DestinationResolver} so the
 * distance math stays independent of where the address comes from; a production adapter can
 * replace this whole class with a Maps API client.
 */
public class HaversineEtaCalculatorAdapter implements EtaCalculatorPort {

    private static final double EARTH_RADIUS_KM = 6371.0088;

    private final DestinationResolver destinationResolver;
    private final double averageSpeedKmPerHour;

    public HaversineEtaCalculatorAdapter(DestinationResolver destinationResolver,
                                         double averageSpeedKmPerHour) {
        if (averageSpeedKmPerHour <= 0) {
            throw new IllegalArgumentException("averageSpeedKmPerHour must be > 0");
        }
        this.destinationResolver = destinationResolver;
        this.averageSpeedKmPerHour = averageSpeedKmPerHour;
    }

    @Override
    public int etaMinutes(UUID bookingId, Coordinates providerLocation) {
        Coordinates destination = destinationResolver.destinationFor(bookingId);
        double distanceKm = haversineKm(providerLocation, destination);
        double minutes = (distanceKm / averageSpeedKmPerHour) * 60.0;
        return (int) Math.ceil(minutes);
    }

    private static double haversineKm(Coordinates a, Coordinates b) {
        double dLat = Math.toRadians(b.latitude() - a.latitude());
        double dLon = Math.toRadians(b.longitude() - a.longitude());
        double lat1 = Math.toRadians(a.latitude());
        double lat2 = Math.toRadians(b.latitude());
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.sin(dLon / 2) * Math.sin(dLon / 2) * Math.cos(lat1) * Math.cos(lat2);
        return 2 * EARTH_RADIUS_KM * Math.asin(Math.sqrt(h));
    }

    /**
     * Resolves the Customer's service-address coordinates for a Booking. Backed by the Booking
     * Service in production; kept as a seam so the ETA math is testable in isolation.
     */
    @FunctionalInterface
    public interface DestinationResolver {
        Coordinates destinationFor(UUID bookingId);
    }
}
