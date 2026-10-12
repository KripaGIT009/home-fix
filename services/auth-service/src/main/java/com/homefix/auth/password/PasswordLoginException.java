package com.homefix.auth.password;

import org.springframework.http.HttpStatus;

/**
 * Domain exception for password sign-in failures, carrying the HTTP status and the stable
 * error code the REST layer surfaces through the shared {@code ErrorResponseDto}.
 *
 * <p>The factory methods below are deliberately coarse. A wrong username and a wrong
 * password produce the same {@link #invalidCredentials()} response, so the endpoint cannot
 * be used to enumerate which usernames exist.
 */
public class PasswordLoginException extends RuntimeException {

    private final HttpStatus status;
    private final String errorCode;
    private final Long retryAfterSeconds;

    private PasswordLoginException(HttpStatus status, String errorCode, String message,
                                   Long retryAfterSeconds) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    /** Unknown username, no password set on the account, or a wrong password. */
    public static PasswordLoginException invalidCredentials() {
        return new PasswordLoginException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS",
                "Incorrect email, username or password.", null);
    }

    /** Too many consecutive failures: the username is locked for the configured window. */
    public static PasswordLoginException locked(long retryAfterSeconds) {
        return new PasswordLoginException(HttpStatus.TOO_MANY_REQUESTS, "ACCOUNT_LOCKED",
                "Too many failed sign-in attempts. Try again in " + retryAfterSeconds
                        + " seconds.", retryAfterSeconds);
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getErrorCode() {
        return errorCode;
    }

    /** Optional Retry-After hint (seconds) for the 429 lockout response. */
    public Long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
