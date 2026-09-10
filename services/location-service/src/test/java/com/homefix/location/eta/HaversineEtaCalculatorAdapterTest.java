package com.homefix.location.eta;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.homefix.location.domain.Coordinates;

/**
 * Unit tests for {@link HaversineEtaCalculatorAdapter}: distance-based ETA in whole minutes
 * (Requirement 10.4).
 */
class HaversineEtaCalculatorAdapterTest {

    private final UUID bookingId = UUID.randomUUID();

    @Test
    void sameLocationYieldsZeroEta() {
        Coordinates point = new Coordinates(12.9716, 77.5946);
        HaversineEtaCalculatorAdapter calc =
                new HaversineEtaCalculatorAdapter(id -> point, 30.0);

        assertThat(calc.etaMinutes(bookingId, point)).isZero();
    }

    @Test
    void etaScalesWithDistanceAndSpeed() {
        // Destination ~1 degree of latitude north (~111 km). At 60 km/h that is ~111 minutes.
        Coordinates provider = new Coordinates(12.0, 77.0);
        Coordinates destination = new Coordinates(13.0, 77.0);
        HaversineEtaCalculatorAdapter calc =
                new HaversineEtaCalculatorAdapter(id -> destination, 60.0);

        int eta = calc.etaMinutes(bookingId, provider);

        // Allow a small tolerance around the ~111 min expectation.
        assertThat(eta).isBetween(108, 115);
    }

    @Test
    void etaRoundsUpToWholeMinutes() {
        Coordinates provider = new Coordinates(12.0, 77.0);
        Coordinates destination = new Coordinates(12.001, 77.0); // ~111 m
        HaversineEtaCalculatorAdapter calc =
                new HaversineEtaCalculatorAdapter(id -> destination, 30.0);

        // ~0.22 min -> rounds up to 1.
        assertThat(calc.etaMinutes(bookingId, provider)).isEqualTo(1);
    }
}
