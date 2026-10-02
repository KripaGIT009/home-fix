package com.homefix.complaint.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.http.HttpStatus;

/**
 * Domain exception for complaint lifecycle failures, carrying the HTTP status, a stable error code,
 * and optional detail messages that the REST layer surfaces via the shared {@code ErrorResponseDto}.
 */
public class ComplaintException extends RuntimeException {

    private final HttpStatus status;
    private final String errorCode;
    private final List<String> details = new ArrayList<>();

    public ComplaintException(HttpStatus status, String errorCode, String message) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
    }

    public ComplaintException(HttpStatus status, String errorCode, String message, List<String> details) {
        this(status, errorCode, message);
        if (details != null) {
            this.details.addAll(details);
        }
    }

    public static ComplaintException validation(String message) {
        return new ComplaintException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
    }

    public static ComplaintException validation(String message, List<String> details) {
        return new ComplaintException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message, details);
    }

    public static ComplaintException notFound(String message) {
        return new ComplaintException(HttpStatus.NOT_FOUND, "COMPLAINT_NOT_FOUND", message);
    }

    /** No support agent was available to assign the complaint to (Requirement 16.1). */
    public static ComplaintException noAgentAvailable(String message) {
        return new ComplaintException(HttpStatus.SERVICE_UNAVAILABLE, "NO_AGENT_AVAILABLE", message);
    }

    /** The requested transition is not valid for the complaint's current state. */
    public static ComplaintException invalidTransition(String message) {
        return new ComplaintException(HttpStatus.CONFLICT, "INVALID_COMPLAINT_TRANSITION", message);
    }

    /** The complaint's current state does not allow a refund (Requirement 16.5). */
    public static ComplaintException refundNotAllowed(String message) {
        return new ComplaintException(HttpStatus.CONFLICT, "REFUND_NOT_ALLOWED", message);
    }

    /** The complaint already carries a refund; a complaint is refunded at most once (16.5). */
    public static ComplaintException refundAlreadyRequested(String message) {
        return new ComplaintException(HttpStatus.CONFLICT, "REFUND_ALREADY_REQUESTED", message);
    }

    /** A retry replayed a refund whose Payment Service outcome is not yet recorded. */
    public static ComplaintException refundInProgress(String message) {
        return new ComplaintException(HttpStatus.CONFLICT, "REFUND_IN_PROGRESS", message);
    }

    /** A refund idempotency key was reused with a different amount. */
    public static ComplaintException idempotencyKeyReused(String message) {
        return new ComplaintException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED", message);
    }

    /** The Payment Service call failed with an unknown outcome; Finance_Admin must reconcile it. */
    public static ComplaintException refundOutcomeUnknown(String message) {
        return new ComplaintException(HttpStatus.BAD_GATEWAY, "REFUND_OUTCOME_UNKNOWN", message);
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
