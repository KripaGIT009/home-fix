package com.homefix.verification.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.http.HttpStatus;

/**
 * Domain exception for verification-workflow failures, carrying the HTTP status, a stable
 * error code, and optional detail lines that the REST layer surfaces via the shared
 * {@code ErrorResponseDto}.
 */
public class VerificationException extends RuntimeException {

    private final HttpStatus status;
    private final String errorCode;
    private final List<String> details = new ArrayList<>();

    public VerificationException(HttpStatus status, String errorCode, String message) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
    }

    public VerificationException(HttpStatus status, String errorCode, String message, List<String> details) {
        this(status, errorCode, message);
        if (details != null) {
            this.details.addAll(details);
        }
    }

    public static VerificationException notFound(String message) {
        return new VerificationException(HttpStatus.NOT_FOUND, "VERIFICATION_NOT_FOUND", message);
    }

    public static VerificationException validation(String message) {
        return new VerificationException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
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
