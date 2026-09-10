package com.homefix.payment.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.http.HttpStatus;

/**
 * Domain exception for payment, refund, and settlement failures, carrying the HTTP status, a
 * stable error code, and optional detail messages that the REST layer surfaces via the shared
 * {@code ErrorResponseDto}.
 */
public class PaymentException extends RuntimeException {

    private final HttpStatus status;
    private final String errorCode;
    private final List<String> details = new ArrayList<>();

    public PaymentException(HttpStatus status, String errorCode, String message) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
    }

    public PaymentException(HttpStatus status, String errorCode, String message, List<String> details) {
        this(status, errorCode, message);
        if (details != null) {
            this.details.addAll(details);
        }
    }

    public static PaymentException validation(String message) {
        return new PaymentException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
    }

    public static PaymentException notFound(String message) {
        return new PaymentException(HttpStatus.NOT_FOUND, "TRANSACTION_NOT_FOUND", message);
    }

    /** Requirement 12.4 / Property 12: an invalid state transition is a 409 Conflict. */
    public static PaymentException invalidTransition(String message) {
        return new PaymentException(HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION", message);
    }

    /** Requirement 12.5: an invalid gateway callback signature is a 400 Bad Request. */
    public static PaymentException invalidSignature(String message) {
        return new PaymentException(HttpStatus.BAD_REQUEST, "INVALID_CALLBACK_SIGNATURE", message);
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
