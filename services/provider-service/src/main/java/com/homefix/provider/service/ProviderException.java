package com.homefix.provider.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.http.HttpStatus;

/**
 * Domain exception for provider-profile, availability, wallet, and settlement failures,
 * carrying the HTTP status, a stable error code, and optional validation details that the
 * REST layer surfaces via the shared {@code ErrorResponseDto}.
 */
public class ProviderException extends RuntimeException {

    private final HttpStatus status;
    private final String errorCode;
    private final List<String> details = new ArrayList<>();

    public ProviderException(HttpStatus status, String errorCode, String message) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
    }

    public ProviderException(HttpStatus status, String errorCode, String message, List<String> details) {
        this(status, errorCode, message);
        if (details != null) {
            this.details.addAll(details);
        }
    }

    public static ProviderException validation(String message) {
        return new ProviderException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
    }

    public static ProviderException notFound(String message) {
        return new ProviderException(HttpStatus.NOT_FOUND, "PROVIDER_NOT_FOUND", message);
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
