package com.homefix.location.service;

import java.time.Instant;
import java.util.UUID;

import com.homefix.location.domain.Coordinates;

/**
 * The tracking-view response for a Booking (Requirement 10.3, 10.7): the last known Provider
 * coordinates, the instant they were recorded, and a {@code stale} flag that is set when the
 * last update is older than the configured staleness threshold so the Customer knows the
 * position may be out of date.
 */
public record TrackingView(UUID bookingId,
                           UUID providerId,
                           Coordinates coordinates,
                           Instant lastUpdatedAt,
                           boolean stale) {
}
