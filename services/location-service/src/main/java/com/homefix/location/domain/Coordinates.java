package com.homefix.location.domain;

/**
 * A geographic point (WGS-84) reported by a Provider or resolved for a Customer's service
 * address. Latitude is in [-90, 90] and longitude in [-180, 180].
 */
public record Coordinates(double latitude, double longitude) {

    public Coordinates {
        if (latitude < -90.0 || latitude > 90.0) {
            throw new IllegalArgumentException("latitude out of range [-90, 90]: " + latitude);
        }
        if (longitude < -180.0 || longitude > 180.0) {
            throw new IllegalArgumentException("longitude out of range [-180, 180]: " + longitude);
        }
    }
}
