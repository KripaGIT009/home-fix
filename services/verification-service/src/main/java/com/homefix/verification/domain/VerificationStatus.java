package com.homefix.verification.domain;

/**
 * Provider verification lifecycle states (Requirement 5.1).
 *
 * <p>{@link #APPROVED}, {@link #REJECTED}, and {@link #SUSPENDED} are the only
 * terminal-eligible states. Note that {@code APPROVED} and {@code SUSPENDED} form a
 * reversible pair (a suspended provider can be reinstated), so neither is strictly
 * terminal; {@code REJECTED} is effectively terminal in the defined map.
 */
public enum VerificationStatus {
    PENDING,
    DOCUMENT_SUBMITTED,
    DOCUMENT_VERIFIED,
    BACKGROUND_CHECK_PENDING,
    BACKGROUND_CHECK_COMPLETED,
    APPROVED,
    REJECTED,
    SUSPENDED
}
