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
 *
 * <p><b>Atomicity contract.</b> Rotation must be single-use even under concurrent refreshes of the
 * same token, so the check-and-consume step is one operation ({@link #consume}) rather than a
 * read followed by a write. Implementations must guarantee that, of any number of concurrent
 * {@code consume} calls for one token, exactly one observes it unused. Likewise a family revoked
 * by {@link #revokeFamily} must stay revoked: a successor {@link #save saved} into it afterwards
 * is refused, so a rotation that raced a replay cannot leave a live token behind.
 */
public interface RefreshTokenStore {

    /**
     * Persists a freshly issued refresh token for the given subject and family with the
     * supplied time-to-live. The token is stored as not-yet-used.
     *
     * @return {@code true} if stored; {@code false} if the family has been revoked, in which case
     *         nothing is stored and the token must not be handed to the client
     */
    boolean save(String token, String subject, String familyId, Duration ttl);

    /**
     * @return the stored record for the token, or empty if the token is unknown, expired, or
     *         was revoked (logout / family invalidation).
     */
    Optional<RefreshTokenRecord> find(String token);

    /**
     * Atomically marks the token as rotated (used) and returns its record <em>as it was before
     * this call</em>, refreshing its TTL to {@code ttl}:
     * <ul>
     *   <li>empty: the token is unknown, expired, or revoked; nothing changed;</li>
     *   <li>a record with {@code used == false}: this caller consumed the token and is the only
     *       one that ever will;</li>
     *   <li>a record with {@code used == true}: the token had already been rotated, so this is a
     *       replay (Requirement 1.10).</li>
     * </ul>
     */
    Optional<RefreshTokenRecord> consume(String token, Duration ttl);

    /**
     * Revokes a single refresh token. Logout (Requirement 1.12) revokes the token's whole family
     * via {@link #revokeFamily} instead, so a token rotated from it does not outlive the session.
     */
    void revoke(String token);

    /**
     * Revokes every token belonging to the family so no member can produce a new access token
     * (Requirement 1.10, Property 26). Implementations must ensure a subsequent {@link #find} of
     * any family member returns empty, and that any later {@link #save} into the family is
     * refused.
     */
    void revokeFamily(String familyId);

    /**
     * Revokes every family holding a token for {@code subject}, ending all of the account's
     * sessions at once (account suspension, Requirement 19.2). Each family is revoked exactly as
     * {@link #revokeFamily} does, so it stays revoked. Families whose tokens were stored before
     * the store indexed them by subject cannot be found this way; they remain unusable while the
     * account is disabled because rotation re-checks the account's status.
     */
    void revokeAllForSubject(String subject);
}
