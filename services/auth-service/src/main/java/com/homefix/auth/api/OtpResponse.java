package com.homefix.auth.api;

/**
 * Response for a successful {@code POST /auth/register/otp} call.
 *
 * @param status       always {@code "OTP_SENT"}
 * @param expiresInSeconds OTP validity window in seconds
 */
public record OtpResponse(String status, long expiresInSeconds) {

    public static OtpResponse sent(long expiresInSeconds) {
        return new OtpResponse("OTP_SENT", expiresInSeconds);
    }
}
