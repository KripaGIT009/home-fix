package com.homefix.verification.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the verification state machine (Requirement 5.1, 5.2, Property 24).
 *
 * <p>The permitted map is duplicated here independently of the production
 * {@link VerificationStateMachine} so the test asserts the <em>specified</em> transitions
 * rather than merely mirroring the implementation. Every ordered pair of states is exercised:
 * pairs in the specified map must be permitted, and every other pair must be rejected
 * (Property 24: permit iff the target is in the permitted set for the source).
 */
class VerificationStateMachineTest {

    /** The permitted transitions exactly as specified in Requirement 5.1 / the design. */
    private static final Map<VerificationStatus, Set<VerificationStatus>> SPEC =
            new EnumMap<>(VerificationStatus.class);

    static {
        SPEC.put(VerificationStatus.PENDING,
                EnumSet.of(VerificationStatus.DOCUMENT_SUBMITTED));
        SPEC.put(VerificationStatus.DOCUMENT_SUBMITTED,
                EnumSet.of(VerificationStatus.DOCUMENT_VERIFIED, VerificationStatus.REJECTED));
        SPEC.put(VerificationStatus.DOCUMENT_VERIFIED,
                EnumSet.of(VerificationStatus.BACKGROUND_CHECK_PENDING));
        SPEC.put(VerificationStatus.BACKGROUND_CHECK_PENDING,
                EnumSet.of(VerificationStatus.BACKGROUND_CHECK_COMPLETED));
        SPEC.put(VerificationStatus.BACKGROUND_CHECK_COMPLETED,
                EnumSet.of(VerificationStatus.APPROVED, VerificationStatus.REJECTED));
        SPEC.put(VerificationStatus.APPROVED,
                EnumSet.of(VerificationStatus.SUSPENDED));
        SPEC.put(VerificationStatus.SUSPENDED,
                EnumSet.of(VerificationStatus.APPROVED));
        SPEC.put(VerificationStatus.REJECTED,
                EnumSet.noneOf(VerificationStatus.class));
    }

    private static Set<VerificationStatus> permittedTargets(VerificationStatus from) {
        return SPEC.getOrDefault(from, EnumSet.noneOf(VerificationStatus.class));
    }

    // ============================= Each valid transition =========================

    @Test
    void everySpecifiedTransition_isPermitted() {
        for (VerificationStatus from : VerificationStatus.values()) {
            for (VerificationStatus to : permittedTargets(from)) {
                assertThat(VerificationStateMachine.isPermitted(from, to))
                        .as("permitted transition %s -> %s", from, to)
                        .isTrue();
            }
        }
    }

    // ============================= Each invalid transition =======================

    @Test
    void everyNonSpecifiedTransition_isRejected() {
        for (VerificationStatus from : VerificationStatus.values()) {
            Set<VerificationStatus> permitted = permittedTargets(from);
            for (VerificationStatus to : VerificationStatus.values()) {
                if (permitted.contains(to)) {
                    continue;
                }
                assertThat(VerificationStateMachine.isPermitted(from, to))
                        .as("disallowed transition %s -> %s (incl. self-transition)", from, to)
                        .isFalse();
            }
        }
    }

    // ============================= Property 24 (iff) =============================

    @Test
    void permittedIffTargetInPermittedSet_holdsForAllPairs() {
        for (VerificationStatus from : VerificationStatus.values()) {
            for (VerificationStatus to : VerificationStatus.values()) {
                boolean expected = permittedTargets(from).contains(to);
                assertThat(VerificationStateMachine.isPermitted(from, to))
                        .as("Property 24: %s -> %s permitted iff in map", from, to)
                        .isEqualTo(expected);
            }
        }
    }

    @Test
    void selfTransitions_areNeverPermitted() {
        for (VerificationStatus s : VerificationStatus.values()) {
            assertThat(VerificationStateMachine.isPermitted(s, s))
                    .as("self-transition %s -> %s", s, s)
                    .isFalse();
        }
    }

    @Test
    void rejectedIsTerminal_noOutgoingTransitions() {
        for (VerificationStatus to : VerificationStatus.values()) {
            assertThat(VerificationStateMachine.isPermitted(VerificationStatus.REJECTED, to))
                    .as("REJECTED is terminal, so REJECTED -> %s is disallowed", to)
                    .isFalse();
        }
        assertThat(VerificationStateMachine.permittedTargets(VerificationStatus.REJECTED)).isEmpty();
    }

    @Test
    void approvedSuspendedReinstatePair_isReversible() {
        assertThat(VerificationStateMachine.isPermitted(
                VerificationStatus.APPROVED, VerificationStatus.SUSPENDED)).isTrue();
        assertThat(VerificationStateMachine.isPermitted(
                VerificationStatus.SUSPENDED, VerificationStatus.APPROVED)).isTrue();
    }
}
