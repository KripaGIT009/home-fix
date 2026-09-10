package com.homefix.promotion.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.http.HttpStatus;

/**
 * Domain exception for coupon creation, validation, redemption, and cancellation failures, carrying
 * the HTTP status, a stable error code, and optional detail messages that the REST layer surfaces
 * via the shared {@code ErrorResponseDto}.
 */
public class CouponException extends RuntimeException {

    private final HttpStatus status;
    private final String errorCode;
    private final List<String> details = new ArrayList<>();

    public CouponException(HttpStatus status, String errorCode, String message) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
    }

    /** Attribute validation failure on Admin coupon creation/update (Requirement 21.1) — 400. */
    public static CouponException validation(String message) {
        return new CouponException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
    }

    public static CouponException notFound(String message) {
        return new CouponException(HttpStatus.NOT_FOUND, "COUPON_NOT_FOUND", message);
    }

    /** Duplicate coupon code (case-insensitive) on creation (Requirement 21.1) — 409. */
    public static CouponException duplicateCode(String message) {
        return new CouponException(HttpStatus.CONFLICT, "DUPLICATE_COUPON_CODE", message);
    }

    /**
     * A checkout-time constraint was violated (active status, date range, minimum order value,
     * per-user usage limit, total usage limit) (Requirement 21.2). Surfaced as a 422 with an
     * error code that identifies the specific violated constraint (see {@link ConstraintCode}).
     */
    public static CouponException constraintViolated(ConstraintCode code, String message) {
        return new CouponException(HttpStatus.UNPROCESSABLE_ENTITY, code.name(), message);
    }

    /**
     * A concurrent redemption/cancellation could not be applied atomically after the configured
     * number of optimistic-lock retries (Requirement 21.3, Property 20) — 409, safe to retry.
     */
    public static CouponException concurrentConflict(String message) {
        return new CouponException(HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION", message);
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

    /**
     * Stable error codes identifying which checkout constraint a coupon violated (Requirement 21.2),
     * so the caller can present a precise message to the customer.
     */
    public enum ConstraintCode {
        COUPON_INACTIVE,
        COUPON_NOT_STARTED,
        COUPON_EXPIRED,
        MIN_ORDER_VALUE_NOT_MET,
        PER_USER_LIMIT_REACHED,
        TOTAL_LIMIT_REACHED
    }
}
