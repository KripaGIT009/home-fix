package com.homefix.auth.token;

import org.springframework.http.HttpStatus;

/**
 * Domain exception for refresh/logout/social-token failures, carrying the HTTP status and a
 * stable error code the REST layer surfaces via the shared {@code ErrorResponseDto}.
 *
 * <p>Authentication-token failures map to {@code 401 Unauthorized} (Requirement 1.5, 1.10,
 * 1.15): an invalid, expired, revoked, or replayed token requires the client to
 * re-authenticate.
 */
public class TokenException extends RuntimeException {

    private final HttpStatus status;
    private final String errorCode;

    public TokenException(HttpStatus status, String errorCode, String message) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
    }

    /** 401 for a refresh token that is unknown, expired, or explicitly revoked (Requirement 1.15). */
    public static TokenException invalidRefreshToken() {
        return new TokenException(HttpStatus.UNAUTHORIZED, "REFRESH_TOKEN_INVALID",
                "Refresh token is invalid, expired, or revoked. Re-authenticate to continue.");
    }

    /** 401 for a replayed refresh token whose family has been invalidated (Requirement 1.10). */
    public static TokenException replayDetected() {
        return new TokenException(HttpStatus.UNAUTHORIZED, "REFRESH_TOKEN_REPLAY",
                "Refresh token reuse detected. The token family has been invalidated; re-authenticate.");
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
