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
}
