package com.homefix.pricing.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.http.HttpStatus;

/**
 * Domain exception for pricing failures, carrying the HTTP status, a stable error code, and
 * optional details (e.g. the violated coupon constraint or the permitted override range) that
 * the REST layer surfaces via the shared {@code ErrorResponseDto}.
 */
public class PricingException extends RuntimeException {

    private final HttpStatus status;
    private final String errorCode;
    private final List<String> details = new ArrayList<>();

    public PricingException(HttpStatus status, String errorCode, String message) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
    }

    public PricingException(HttpStatus status, String errorCode, String message, List<String> details) {
        this(status, errorCode, message);
        if (details != null) {
            this.details.addAll(details);
        }
    }

    public static PricingException validation(String message) {
        return new PricingException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
    }

    public static PricingException parametersNotFound(String message) {
        return new PricingException(HttpStatus.NOT_FOUND, "PRICING_PARAMETERS_NOT_FOUND", message);
    }

    /** A violated coupon constraint (Requirement 6.10). */
    public static PricingException coupon(String errorCode, String message) {
        return new PricingException(HttpStatus.UNPROCESSABLE_ENTITY, errorCode, message);
    }

    /**
     * The Promotion Service, which quotes coupon discounts, could not be consulted (Requirement
     * 6.10). A 503 so callers treat it as transient and retry, rather than as a bad coupon.
     */
    public static PricingException couponServiceUnavailable(String message) {
        return new PricingException(HttpStatus.SERVICE_UNAVAILABLE, "COUPON_SERVICE_UNAVAILABLE", message);
    }

    /** A provider-specific override outside the permitted floor/ceiling (Requirement 6.12). */
    public static PricingException overrideOutOfRange(String message, List<String> details) {
        return new PricingException(HttpStatus.UNPROCESSABLE_ENTITY, "OVERRIDE_OUT_OF_RANGE", message, details);
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public List<String> getDetails() {
        return details;
    }
}
