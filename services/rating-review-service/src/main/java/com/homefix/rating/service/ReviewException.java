package com.homefix.rating.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.http.HttpStatus;

/**
 * Domain exception for review submission and moderation failures, carrying the HTTP status, a
 * stable error code, and optional detail messages that the REST layer surfaces via the shared
 * {@code ErrorResponseDto}.
 */
public class ReviewException extends RuntimeException {

    private final HttpStatus status;
    private final String errorCode;
    private final List<String> details = new ArrayList<>();

    public ReviewException(HttpStatus status, String errorCode, String message) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
    }

    public ReviewException(HttpStatus status, String errorCode, String message, List<String> details) {
        this(status, errorCode, message);
        if (details != null) {
            this.details.addAll(details);
        }
    }

    public static ReviewException validation(String message) {
        return new ReviewException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
    }

    public static ReviewException notFound(String message) {
        return new ReviewException(HttpStatus.NOT_FOUND, "REVIEW_NOT_FOUND", message);
    }

    /** Requirement 15.2 / Property 15: a submission after the 7-day window is rejected. */
    public static ReviewException windowClosed(String message) {
        return new ReviewException(HttpStatus.CONFLICT, "REVIEW_WINDOW_CLOSED", message);
    }

    /** A second review against the same prompt is rejected. */
    public static ReviewException duplicate(String message) {
        return new ReviewException(HttpStatus.CONFLICT, "DUPLICATE_REVIEW", message);
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
