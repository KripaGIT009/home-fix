package com.homefix.payment.service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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

    /** Requirement 12.5: a validly signed callback whose payload is not the documented JSON contract. */
    public static PaymentException invalidCallbackPayload(String message) {
        return new PaymentException(HttpStatus.BAD_REQUEST, "INVALID_CALLBACK_PAYLOAD", message);
    }

    /**
     * Requirement 12.5: the signed payload does not describe the transaction it was sent for (wrong
     * transaction id, gateway or amount), e.g. a captured payload replayed against another payment.
     */
    public static PaymentException callbackMismatch(String message) {
        return new PaymentException(HttpStatus.BAD_REQUEST, "CALLBACK_MISMATCH", message);
    }

    /** Requirement 12.5: the signed payload's timestamp is outside the accepted window. */
    public static PaymentException callbackStale(String message) {
        return new PaymentException(HttpStatus.BAD_REQUEST, "CALLBACK_STALE", message);
    }

    /** Requirement 12.4/12.5: a callback whose outcome contradicts the transaction's settled state. */
    public static PaymentException callbackConflict(String message) {
        return new PaymentException(HttpStatus.CONFLICT, "CALLBACK_CONFLICT", message);
    }

    /**
     * Requirement 12.2: the booking being paid for does not exist, or is not the caller's. Both answer
     * the same 404 so a customer cannot probe other customers' booking ids.
     */
    public static PaymentException bookingNotFound(UUID bookingId) {
        return new PaymentException(HttpStatus.NOT_FOUND, "BOOKING_NOT_FOUND",
                "Booking " + bookingId + " not found");
    }

    /** Requirement 12.2: the booking's status does not allow a payment (job not done, or paid/closed). */
    public static PaymentException bookingNotPayable(UUID bookingId) {
        return new PaymentException(HttpStatus.CONFLICT, "BOOKING_NOT_PAYABLE",
                "Booking " + bookingId + " cannot be paid in its current state");
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
