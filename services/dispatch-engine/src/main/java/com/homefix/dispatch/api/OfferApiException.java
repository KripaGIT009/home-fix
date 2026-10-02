package com.homefix.dispatch.api;

import org.springframework.http.HttpStatus;

/** A refusal from the provider-facing offer API, carrying the HTTP status and error code to return. */
public class OfferApiException extends RuntimeException {

    private final HttpStatus status;
    private final String errorCode;

    public OfferApiException(HttpStatus status, String errorCode, String message) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
    }

    public HttpStatus status() {
        return status;
    }

    public String errorCode() {
        return errorCode;
    }
}
