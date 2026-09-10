package com.homefix.location.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * The last known Provider location for a Booking as held in the low-latency cache
 * (Requirement 10.2). Carries the coordinates, the Provider that reported them, and the
 * instant the update was recorded — the timestamp drives the staleness check in the tracking
 * view (Requirement 10.7).
 */
public record CachedLocation(UUID bookingId,
                             UUID providerId,
                             Coordinates coordinates,
                             Instant recordedAt) {
}
