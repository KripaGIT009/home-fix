package com.homefix.booking.service;

import org.springframework.http.HttpStatus;

/**
 * Domain exception for booking failures, carrying the HTTP status and stable error code the
 * REST layer surfaces via the shared {@code ErrorResponseDto} (Task 5).
 */
public class BookingException extends RuntimeException {

    private final HttpStatus status;
    private final String errorCode;

    public BookingException(HttpStatus status, String errorCode, String message) {
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

    public static BookingException notFound(String reference) {
        return new BookingException(HttpStatus.NOT_FOUND, "BOOKING_NOT_FOUND",
                "Booking not found: " + reference);
    }

    /** Requested subcategory/category is inactive or unavailable (Requirement 7.6). */
    public static BookingException serviceUnavailable(String message) {
        return new BookingException(HttpStatus.UNPROCESSABLE_ENTITY, "SERVICE_UNAVAILABLE", message);
    }

    /** Scheduled time below the minimum lead time (Requirement 7.7). */
    public static BookingException leadTime(String message) {
        return new BookingException(HttpStatus.UNPROCESSABLE_ENTITY, "LEAD_TIME_TOO_SHORT", message);
    }

    /** Scheduled time beyond the maximum horizon (Requirement 7.8). */
    public static BookingException horizon(String message) {
        return new BookingException(HttpStatus.UNPROCESSABLE_ENTITY, "SCHEDULING_HORIZON_EXCEEDED", message);
    }

    /** Pricing Engine unavailable — no booking created (Requirement 7.4). */
    public static BookingException pricingUnavailable() {
        return new BookingException(HttpStatus.SERVICE_UNAVAILABLE, "PRICING_ENGINE_UNAVAILABLE",
                "The pricing service is temporarily unavailable; please try again shortly");
    }

    public static BookingException validation(String message) {
        return new BookingException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
    }

    /** Media upload violated the count/size/format constraints (Requirement 7.2). */
    public static BookingException media(String message) {
        return new BookingException(HttpStatus.UNPROCESSABLE_ENTITY, "MEDIA_VALIDATION_ERROR", message);
    }

    /**
     * A required before/after photo was not attached for a JOB_STARTED / JOB_COMPLETED
     * transition (Requirement 9.10, 9.11, 11.2, 11.4). Surfaced as 422 so the client knows to
     * upload a photo before retrying.
     */
    public static BookingException photoRequired(String message) {
        return new BookingException(HttpStatus.UNPROCESSABLE_ENTITY, "PHOTO_REQUIRED", message);
    }

    /**
     * Payment was requested for a booking that is not awaiting one: the job is not finished yet,
     * or the booking was paid, cancelled, disputed or refunded (Requirement 12.1).
     */
    public static BookingException notPayable(String message) {
        return new BookingException(HttpStatus.CONFLICT, "BOOKING_NOT_PAYABLE", message);
    }

    /** Saga failed and was compensated; the booking request was not completed (Requirement 24.7). */
    public static BookingException sagaFailed(String message) {
        return new BookingException(HttpStatus.INTERNAL_SERVER_ERROR, "BOOKING_NOT_COMPLETED", message);
    }
}
