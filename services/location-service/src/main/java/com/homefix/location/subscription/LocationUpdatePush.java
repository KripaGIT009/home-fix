package com.homefix.location.subscription;

import com.homefix.location.domain.Coordinates;

/**
 * The payload pushed to every subscriber of a Booking's location feed: the Provider's latest
 * coordinates plus the freshly recalculated ETA in minutes (Requirement 10.2, 10.4).
 */
public record LocationUpdatePush(Coordinates coordinates, int etaMinutes) {
}
