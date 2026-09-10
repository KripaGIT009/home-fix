package com.homefix.customer.support;

import java.util.Optional;

import com.homefix.customer.geocoding.GeocodingPort;

/**
 * Configurable {@link GeocodingPort} test double: resolves to a fixed address by default,
 * or simulates a geocoding failure when {@link #failNext()} is set.
 */
public class FakeGeocodingPort implements GeocodingPort {

    private boolean fail = false;
    private String resolved = "221B Baker Street, London";

    public void failNext() {
        this.fail = true;
    }

    public void resolveTo(String address) {
        this.resolved = address;
        this.fail = false;
    }

    @Override
    public Optional<String> reverseGeocode(double lat, double lng) {
        return fail ? Optional.empty() : Optional.of(resolved);
    }
}
