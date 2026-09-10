package com.homefix.auth.token;

import java.time.Duration;
import java.util.Optional;

/**
 * Storage abstraction for opaque refresh tokens and their token families
 * (Requirement 1.9, 1.10, 1.12, Property 26).
 *
 * <p>Backed by Redis in production so the 30-day TTL (Requirement 1.8) is enforced by key
 * expiry. Abstracted behind an interface so refresh rotation, replay detection, and logout
 * revocation can be unit-tested against an in-memory fake with no Redis dependency.
 */
public interface RefreshTokenStore {

    /**
     * Persists a freshly issued refresh token for the given subject and family with the
     * supplied time-to-live. The token is stored as not-yet-used.
     */
    void save(String token, String subject, String familyId, Duration ttl);

    /**
     * @return the stored record for the token, or empty if the token is unknown, expired, or
     *         was revoked (logout / family invalidation).
     */
    Optional<RefreshTokenRecord> find(String token);

    /**
     * Marks a token as rotated (used). A subsequent {@link #find} that observes {@code used}
     * indicates a replay attempt.
     */
    void markUsed(String token, Duration ttl);

    /**
     * Revokes a single refresh token (e.g. on logout, Requirement 1.12).
     */
    void revoke(String token);

    /**
     * Revokes every token belonging to the family so no member can produce a new access token
     * (Requirement 1.10, Property 26). Implementations must ensure a subsequent {@link #find}
     * of any family member returns empty.
     */
    void revokeFamily(String familyId);
}
