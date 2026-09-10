package com.homefix.location.api.dto;

import java.time.Instant;
import java.util.UUID;

import com.homefix.location.service.TrackingView;

/**
 * Tracking-view response for a Customer (Requirement 10.3, 10.7). When {@code stale} is true
 * the coordinates are the last known position but older than the staleness threshold;
 * {@code lastUpdatedAt} lets the client indicate the position may be out of date.
 */
public record TrackingViewResponse(UUID bookingId,
                                   UUID providerId,
                                   double latitude,
                                   double longitude,
                                   Instant lastUpdatedAt,
                                   boolean stale) {

    public static TrackingViewResponse from(TrackingView view) {
        return new TrackingViewResponse(
                view.bookingId(),
                view.providerId(),
                view.coordinates().latitude(),
                view.coordinates().longitude(),
                view.lastUpdatedAt(),
                view.stale());
    }
}
