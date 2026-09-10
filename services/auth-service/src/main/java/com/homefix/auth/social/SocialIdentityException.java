package com.homefix.auth.social;

import org.springframework.http.HttpStatus;

/**
 * Raised when a provider identity token cannot be validated (invalid signature, expired,
 * wrong audience, or an unsupported provider).
 *
 * <p>Maps to a {@code 401 Unauthorized} response with an error code identifying the failure
 * reason (Requirement 1.5).
 */
public class SocialIdentityException extends RuntimeException {

    private final String errorCode;

    public SocialIdentityException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public HttpStatus getStatus() {
        return HttpStatus.UNAUTHORIZED;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public static SocialIdentityException invalidToken() {
        return new SocialIdentityException("SOCIAL_IDENTITY_TOKEN_INVALID",
                "The social provider identity token is invalid or malformed.");
    }

    public static SocialIdentityException expiredToken() {
        return new SocialIdentityException("SOCIAL_IDENTITY_TOKEN_EXPIRED",
                "The social provider identity token has expired.");
    }

    public static SocialIdentityException unsupportedProvider(String provider) {
        return new SocialIdentityException("SOCIAL_PROVIDER_UNSUPPORTED",
                "Unsupported social login provider: " + provider);
    }
}
