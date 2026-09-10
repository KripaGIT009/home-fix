package com.homefix.location.event;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Minimal view of the {@code JobStarted} event published by the Booking Service when a Booking
 * transitions to JOB_STARTED (Requirement 9.5). The Location Service only needs the
 * {@code bookingId} to terminate that Booking's location feed (Requirement 10.5); any other
 * fields on the event payload are ignored.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record JobStartedEvent(UUID bookingId) {
}
