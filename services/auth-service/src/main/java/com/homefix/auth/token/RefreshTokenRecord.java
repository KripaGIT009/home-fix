package com.homefix.auth.token;

/**
 * A stored refresh token and the metadata used for rotation and replay detection
 * (Requirement 1.9, 1.10, Property 26).
 *
 * <p>Every refresh token belongs to a <em>token family</em> — a lineage of tokens produced by
 * successive rotations starting from a single authentication event. When a token is rotated
 * its successor stays in the same family. Detecting reuse of an already-rotated token means an
 * attacker (or a buggy client) is replaying a token, so the whole family is invalidated.
 *
 * @param subject  the user account id the token authenticates
 * @param familyId identifier shared by every token derived from one authentication event
 * @param used     {@code true} once this token has been rotated; a second use is a replay
 */
public record RefreshTokenRecord(String subject, String familyId, boolean used) {

    /** A freshly issued, not-yet-rotated token record. */
    public static RefreshTokenRecord fresh(String subject, String familyId) {
        return new RefreshTokenRecord(subject, familyId, false);
    }

    /** A copy of this record marked as rotated (used). */
    public RefreshTokenRecord markUsed() {
        return new RefreshTokenRecord(subject, familyId, true);
    }
}
