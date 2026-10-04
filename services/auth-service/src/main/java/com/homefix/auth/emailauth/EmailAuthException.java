package com.homefix.auth.emailauth;

import org.springframework.http.HttpStatus;

/**
 * A refused email sign-up, code, password, credential or invitation request (email-auth spec),
 * carrying the HTTP status and stable error code the REST layer returns in the shared envelope.
 */
public class EmailAuthException extends RuntimeException {

    private final HttpStatus status;
    private final String errorCode;
    private final Long retryAfterSeconds;

    public EmailAuthException(HttpStatus status, String errorCode, String message) {
        this(status, errorCode, message, null);
    }

    public EmailAuthException(HttpStatus status, String errorCode, String message, Long retryAfterSeconds) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public static EmailAuthException tooManyRequests(long retryAfterSeconds) {
        return new EmailAuthException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_REQUESTS",
                "Too many requests. Try again in " + retryAfterSeconds + " seconds.", retryAfterSeconds);
    }

    public static EmailAuthException invalidCode(int attemptsLeft) {
        return new EmailAuthException(HttpStatus.BAD_REQUEST, "INVALID_CODE",
                "That code is not right. " + attemptsLeft + " attempt(s) left.");
    }

    public static EmailAuthException codeExpired() {
        return new EmailAuthException(HttpStatus.GONE, "CODE_EXPIRED",
                "This code has expired or was used up. Ask for a new one.");
    }

    public static EmailAuthException weakPassword(String reason) {
        return new EmailAuthException(HttpStatus.BAD_REQUEST, "WEAK_PASSWORD", reason);
    }

    public static EmailAuthException invalidRole() {
        return new EmailAuthException(HttpStatus.BAD_REQUEST, "INVALID_ROLE",
                "Sign-up is available for customers and service providers only.");
    }

    public static EmailAuthException mobileInUse() {
        return new EmailAuthException(HttpStatus.CONFLICT, "MOBILE_IN_USE",
                "This mobile number already has a HomeFix account. Sign in with it by OTP, then add "
                        + "your email and a password in your profile.");
    }

    public static EmailAuthException emailInUse() {
        return new EmailAuthException(HttpStatus.CONFLICT, "EMAIL_IN_USE",
                "This email address belongs to another account.");
    }

    public static EmailAuthException currentPasswordIncorrect() {
        return new EmailAuthException(HttpStatus.FORBIDDEN, "CURRENT_PASSWORD_INCORRECT",
                "Your current password is not right.");
    }

    public static EmailAuthException emailDeliveryFailed() {
        return new EmailAuthException(HttpStatus.BAD_GATEWAY, "EMAIL_DELIVERY_FAILED",
                "We could not send the email. Please try again.");
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getErrorCode() {
        return errorCode;
    }

    /** Optional Retry-After hint, in seconds, for a 429. */
    public Long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
