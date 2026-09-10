package com.homefix.verification.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.homefix.verification.service.VerificationException;

import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import org.springframework.http.HttpStatus;

/**
 * Property-based test for the Verification Service correctness property 24 (design.md "Correctness
 * Properties", Requirements 5.1 / 5.2). Runs a minimum of 100 tries and is tagged with the required
 * {@code Feature: homefix-platform, Property N} label.
 *
 * <p>Complements the example-based {@link VerificationStateMachineTest} by asserting universally
 * that, for any (source, target) pair of statuses, the {@link Verification} aggregate applies the
 * transition <em>if and only if</em> the target is in the defined permitted map for the source, and
 * otherwise rejects it with a 409 error whose details identify the current and disallowed states.
 *
 * <p>The generated {@code from} state is materialised by walking a known permitted path from the
 * initial {@code PENDING} state, so the aggregate under test is a genuine {@link Verification}
 * instance in exactly that state (its real {@code transitionTo} guard is what we exercise).
 */
class VerificationPropertiesTest {

    private static final UUID ACTOR = UUID.fromString("99999999-9999-9999-9999-999999999999");

    /** A known permitted path from PENDING to each reachable state, used to set up {@code from}. */
    private static final Map<VerificationStatus, List<VerificationStatus>> PATH_TO = Map.of(
            VerificationStatus.PENDING, List.of(),
            VerificationStatus.DOCUMENT_SUBMITTED, List.of(VerificationStatus.DOCUMENT_SUBMITTED),
            VerificationStatus.DOCUMENT_VERIFIED, List.of(VerificationStatus.DOCUMENT_SUBMITTED,
                    VerificationStatus.DOCUMENT_VERIFIED),
            VerificationStatus.BACKGROUND_CHECK_PENDING, List.of(VerificationStatus.DOCUMENT_SUBMITTED,
                    VerificationStatus.DOCUMENT_VERIFIED, VerificationStatus.BACKGROUND_CHECK_PENDING),
            VerificationStatus.BACKGROUND_CHECK_COMPLETED, List.of(VerificationStatus.DOCUMENT_SUBMITTED,
                    VerificationStatus.DOCUMENT_VERIFIED, VerificationStatus.BACKGROUND_CHECK_PENDING,
                    VerificationStatus.BACKGROUND_CHECK_COMPLETED),
            VerificationStatus.APPROVED, List.of(VerificationStatus.DOCUMENT_SUBMITTED,
                    VerificationStatus.DOCUMENT_VERIFIED, VerificationStatus.BACKGROUND_CHECK_PENDING,
                    VerificationStatus.BACKGROUND_CHECK_COMPLETED, VerificationStatus.APPROVED),
            VerificationStatus.REJECTED, List.of(VerificationStatus.DOCUMENT_SUBMITTED,
                    VerificationStatus.REJECTED),
            VerificationStatus.SUSPENDED, List.of(VerificationStatus.DOCUMENT_SUBMITTED,
                    VerificationStatus.DOCUMENT_VERIFIED, VerificationStatus.BACKGROUND_CHECK_PENDING,
                    VerificationStatus.BACKGROUND_CHECK_COMPLETED, VerificationStatus.APPROVED,
                    VerificationStatus.SUSPENDED));

    // ============================================================================================
    // Property 24: Verification state machine valid transitions only
    // ============================================================================================

    @Property(tries = 200)
    @Label("Feature: homefix-platform, Property 24: Verification state machine valid transitions only")
    void transitionPermittedIffTargetInPermittedMap(
            @ForAll VerificationStatus from,
            @ForAll VerificationStatus to) {

        Verification verification = verificationInState(from);
        assertThat(verification.getStatus()).isEqualTo(from);

        boolean expectedPermitted = VerificationStateMachine.permittedTargets(from).contains(to);

        if (expectedPermitted) {
            verification.transitionTo(to, ACTOR, "reason");
            assertThat(verification.getStatus()).isEqualTo(to);
        } else {
            assertThatThrownBy(() -> verification.transitionTo(to, ACTOR, "reason"))
                    .isInstanceOf(VerificationException.class)
                    .satisfies(ex -> {
                        VerificationException ve = (VerificationException) ex;
                        assertThat(ve.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(ve.getErrorCode()).isEqualTo("INVALID_STATE_TRANSITION");
                        // The error names both the current state and the disallowed target.
                        assertThat(ve.getDetails()).contains("currentState=" + from,
                                "disallowedTarget=" + to);
                    });
            // The rejected transition left the state unchanged.
            assertThat(verification.getStatus()).isEqualTo(from);
        }
    }

    /** Builds a {@link Verification} in {@code target} by walking a known permitted path. */
    private Verification verificationInState(VerificationStatus target) {
        Verification verification = Verification.create(UUID.randomUUID());
        for (VerificationStatus step : PATH_TO.get(target)) {
            verification.transitionTo(step, ACTOR, "setup");
        }
        return verification;
    }
}
