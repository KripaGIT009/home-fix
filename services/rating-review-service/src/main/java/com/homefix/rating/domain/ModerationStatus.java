package com.homefix.rating.domain;

/**
 * A review's moderation state as the Admin Portal shows it (Requirement 19.2, Requirement 15.5,
 * 15.9). Not stored: it is derived from the {@link Review#isActive() active} and
 * {@link Review#isFlagged() flagged} columns, which remain the source of truth.
 *
 * <ul>
 *   <li>{@link #FLAGGED} — active and held by fraud detection for moderation (15.5).</li>
 *   <li>{@link #PUBLISHED} — active and not flagged: visible and counted in the aggregate.</li>
 *   <li>{@link #REMOVED} — deactivated by an Admin for a policy violation (15.9).</li>
 *   <li>{@link #PENDING} — part of the portal's vocabulary only. This service publishes or flags a
 *       review at submission, so no review is ever PENDING and filtering by it yields nothing.</li>
 * </ul>
 */
public enum ModerationStatus {
    PENDING,
    FLAGGED,
    PUBLISHED,
    REMOVED;

    /** Derives the moderation state of {@code review}. */
    public static ModerationStatus of(Review review) {
        if (!review.isActive()) {
            return REMOVED;
        }
        return review.isFlagged() ? FLAGGED : PUBLISHED;
    }
}
