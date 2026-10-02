package com.homefix.customer.service;

import java.util.UUID;

import org.springframework.http.HttpStatus;

/**
 * Domain exception for customer profile / address / deletion failures, carrying the HTTP
 * status and stable error code the REST layer surfaces via the shared
 * {@code ErrorResponseDto} (Task 5).
 */
public class CustomerException extends RuntimeException {

    private final HttpStatus status;
    private final String errorCode;

    public CustomerException(HttpStatus status, String errorCode, String message) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public static CustomerException notFound(String message) {
        return new CustomerException(HttpStatus.NOT_FOUND, "CUSTOMER_NOT_FOUND", message);
    }

    /** No saved address has this id (never created, or deleted by its customer). */
    public static CustomerException addressNotFound(UUID addressId) {
        return new CustomerException(HttpStatus.NOT_FOUND, "ADDRESS_NOT_FOUND",
                "Address " + addressId + " not found");
    }

    public static CustomerException addressLimitReached(int max) {
        return new CustomerException(HttpStatus.UNPROCESSABLE_ENTITY, "ADDRESS_LIMIT_REACHED",
                "Maximum of " + max + " saved addresses reached");
    }

    public static CustomerException addressInUse(String bookingReference) {
        return new CustomerException(HttpStatus.CONFLICT, "ADDRESS_IN_USE",
                "Address is in use by active booking " + bookingReference);
    }

    public static CustomerException gpsUnavailable() {
        return new CustomerException(HttpStatus.BAD_REQUEST, "GPS_UNAVAILABLE",
                "GPS access denied or coordinates unavailable; please enter the address manually");
    }
}
