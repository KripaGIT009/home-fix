package com.homefix.auth.token;

/**
 * A freshly issued pair of tokens returned on successful authentication.
 *
 * @param accessToken       short-lived JWT (15-min TTL, Requirement 1.8)
 * @param refreshToken      long-lived opaque refresh token (30-day TTL, Requirement 1.8)
 * @param accessTtlSeconds  access-token lifetime in seconds (for client convenience)
 */
public record TokenPair(String accessToken, String refreshToken, long accessTtlSeconds) {
}
