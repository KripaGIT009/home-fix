package com.homefix.location.service;

import org.springframework.http.HttpStatus;

/**
 * Domain exception carrying an error code and HTTP status so the {@code GlobalExceptionHandler}
 * can translate it into the shared error envelope.
 */
public class LocationException extends RuntimeException {

    private final String errorCode;
    private final HttpStatus status;

    public LocationException(String errorCode, HttpStatus status, String message) {
        super(message);
        this.errorCode = errorCode;
        this.status = status;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public HttpStatus getStatus() {
        return status;
    }

    /** Update rejected because it arrived faster than the 1-per-5 s limit (Requirement 10.1). */
    public static LocationException rateLimited(String message) {
        return new LocationException("LOCATION_UPDATE_RATE_LIMITED", HttpStatus.TOO_MANY_REQUESTS, message);
    }

    /** Update rejected because the Booking's feed has been terminated (Requirement 10.5). */
    public static LocationException bookingTerminated(String message) {
        return new LocationException("LOCATION_BOOKING_TERMINATED", HttpStatus.CONFLICT, message);
    }

    /** Tracking view requested for a Booking with no location yet cached. */
    public static LocationException noLocation(String message) {
        return new LocationException("LOCATION_NOT_AVAILABLE", HttpStatus.NOT_FOUND, message);
    }

    /**
     * The Booking does not exist, or the caller is neither its customer nor its assigned provider.
     * Both get the same answer so a booking id cannot be probed.
     */
    public static LocationException bookingNotFound(String message) {
        return new LocationException("LOCATION_BOOKING_NOT_FOUND", HttpStatus.NOT_FOUND, message);
    }

    /** The Booking Service could not say who a Booking is between, so access is refused. */
    public static LocationException bookingLookupUnavailable(String message) {
        return new LocationException("LOCATION_BOOKING_LOOKUP_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE,
                message);
    }
}
