package com.homefix.location.api.dto;

import com.homefix.location.subscription.LocationUpdatePush;

/**
 * Response for an accepted location update: the accepted coordinates echoed back plus the
 * recalculated ETA in minutes that was pushed to subscribers (Requirement 10.4).
 */
public record LocationUpdateResponse(double latitude, double longitude, int etaMinutes) {

    public static LocationUpdateResponse from(LocationUpdatePush push) {
        return new LocationUpdateResponse(
                push.coordinates().latitude(),
                push.coordinates().longitude(),
                push.etaMinutes());
    }
}
