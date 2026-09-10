package com.homefix.auth.api;

import java.util.List;

import com.homefix.auth.token.TokenPair;

/**
 * Response for a successful {@code POST /auth/register/verify} call.
 */
public record TokenResponse(
        String userId,
        List<String> roles,
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresInSeconds) {

    public static TokenResponse from(String userId, List<String> roles, TokenPair pair) {
        return new TokenResponse(
                userId,
                roles,
                pair.accessToken(),
                pair.refreshToken(),
                "Bearer",
                pair.accessTtlSeconds());
    }
}
