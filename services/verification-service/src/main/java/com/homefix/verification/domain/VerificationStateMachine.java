package com.homefix.verification.domain;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The single source of truth for permitted Provider verification transitions (Requirement 5.1,
 * 5.2, Property 24).
 *
 * <p>Permitted map (from the design state machine):
 * <pre>
 *   PENDING                    → DOCUMENT_SUBMITTED
 *   DOCUMENT_SUBMITTED         → DOCUMENT_VERIFIED | REJECTED
 *   DOCUMENT_VERIFIED          → BACKGROUND_CHECK_PENDING
 *   BACKGROUND_CHECK_PENDING   → BACKGROUND_CHECK_COMPLETED
 *   BACKGROUND_CHECK_COMPLETED → APPROVED | REJECTED
 *   APPROVED                   → SUSPENDED
 *   SUSPENDED                  → APPROVED
 *   REJECTED                   → (none)
 * </pre>
 *
 * <p>A transition is permitted <em>if and only if</em> the target appears in the permitted
 * set for the current source state (Property 24). Any other transition is disallowed.
 */
public final class VerificationStateMachine {

    private static final Map<VerificationStatus, Set<VerificationStatus>> PERMITTED =
            new EnumMap<>(VerificationStatus.class);

    static {
        PERMITTED.put(VerificationStatus.PENDING,
                EnumSet.of(VerificationStatus.DOCUMENT_SUBMITTED));
        PERMITTED.put(VerificationStatus.DOCUMENT_SUBMITTED,
                EnumSet.of(VerificationStatus.DOCUMENT_VERIFIED, VerificationStatus.REJECTED));
        PERMITTED.put(VerificationStatus.DOCUMENT_VERIFIED,
                EnumSet.of(VerificationStatus.BACKGROUND_CHECK_PENDING));
        PERMITTED.put(VerificationStatus.BACKGROUND_CHECK_PENDING,
                EnumSet.of(VerificationStatus.BACKGROUND_CHECK_COMPLETED));
        PERMITTED.put(VerificationStatus.BACKGROUND_CHECK_COMPLETED,
                EnumSet.of(VerificationStatus.APPROVED, VerificationStatus.REJECTED));
        PERMITTED.put(VerificationStatus.APPROVED,
                EnumSet.of(VerificationStatus.SUSPENDED));
        PERMITTED.put(VerificationStatus.SUSPENDED,
                EnumSet.of(VerificationStatus.APPROVED));
        PERMITTED.put(VerificationStatus.REJECTED,
                EnumSet.noneOf(VerificationStatus.class));
    }

    private VerificationStateMachine() {
    }

    /**
     * @return {@code true} iff transitioning from {@code from} to {@code to} is permitted by
     *         the defined map. A no-op self-transition ({@code from == to}) is not permitted
     *         unless the map explicitly allows it (it never does).
     */
    public static boolean isPermitted(VerificationStatus from, VerificationStatus to) {
        return PERMITTED.getOrDefault(from, EnumSet.noneOf(VerificationStatus.class)).contains(to);
    }

    /** @return the immutable set of target states permitted from {@code from}. */
    public static Set<VerificationStatus> permittedTargets(VerificationStatus from) {
        return EnumSet.copyOf(PERMITTED.getOrDefault(from, EnumSet.noneOf(VerificationStatus.class)));
    }
}
