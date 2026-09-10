package com.homefix.location.service;

import java.util.UUID;

import com.homefix.location.domain.Coordinates;

/**
 * A single Provider GPS update submitted for a Booking (Requirement 10.1).
 */
public record LocationUpdateCommand(UUID bookingId, UUID providerId, Coordinates coordinates) {
}
