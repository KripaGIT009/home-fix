package com.homefix.auth.registration;

import org.springframework.http.HttpStatus;

/**
 * Domain exception for registration/OTP failures, carrying the HTTP status and stable
 * error code the REST layer should surface via the shared {@code ErrorResponseDto}.
 */
public class RegistrationException extends RuntimeException {

    private final HttpStatus status;
    private final String errorCode;
    private final Long retryAfterSeconds;

    public RegistrationException(HttpStatus status, String errorCode, String message) {
        this(status, errorCode, message, null);
    }

    public RegistrationException(HttpStatus status, String errorCode, String message, Long retryAfterSeconds) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getErrorCode() {
        return errorCode;
    }

    /** Optional Retry-After hint (seconds) for 429 responses (lockout / rate limit). */
    public Long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
