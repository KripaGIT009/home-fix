package com.homefix.booking.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static com.homefix.booking.domain.BookingStatus.ADDITIONAL_QUOTE_REQUIRED;
import static com.homefix.booking.domain.BookingStatus.CANCELLED;
import static com.homefix.booking.domain.BookingStatus.CREATED;
import static com.homefix.booking.domain.BookingStatus.CUSTOMER_APPROVAL_PENDING;
import static com.homefix.booking.domain.BookingStatus.CUSTOMER_CONFIRMED;
import static com.homefix.booking.domain.BookingStatus.DISPUTED;
import static com.homefix.booking.domain.BookingStatus.JOB_COMPLETED;
import static com.homefix.booking.domain.BookingStatus.JOB_PAUSED;
import static com.homefix.booking.domain.BookingStatus.JOB_STARTED;
import static com.homefix.booking.domain.BookingStatus.PAYMENT_COMPLETED;
import static com.homefix.booking.domain.BookingStatus.PAYMENT_PENDING;
import static com.homefix.booking.domain.BookingStatus.PROVIDER_ACCEPTED;
import static com.homefix.booking.domain.BookingStatus.PROVIDER_ARRIVED;
import static com.homefix.booking.domain.BookingStatus.PROVIDER_ASSIGNED;
import static com.homefix.booking.domain.BookingStatus.AWAITING_ASSIGNMENT;
import static com.homefix.booking.domain.BookingStatus.PROVIDER_ON_THE_WAY;
import static com.homefix.booking.domain.BookingStatus.REFUNDED;
import static com.homefix.booking.domain.BookingStatus.SEARCHING_FAILED;
import static com.homefix.booking.domain.BookingStatus.SEARCHING_PROVIDER;

/**
 * Exhaustively verifies the Booking state machine against the Requirement 9.1 permitted-
 * transition map (Property 8): every permitted transition is allowed and every other
 * (source, target) pair is rejected.
 */
class BookingStateMachineTest {

    private final BookingStateMachine sm = new BookingStateMachine();

    /** The authoritative expected map, written independently from the production copy. */
    private static Map<BookingStatus, Set<BookingStatus>> expected() {
        Map<BookingStatus, Set<BookingStatus>> m = new EnumMap<>(BookingStatus.class);
        m.put(CREATED, EnumSet.of(SEARCHING_PROVIDER));
        m.put(SEARCHING_PROVIDER, EnumSet.of(PROVIDER_ASSIGNED, AWAITING_ASSIGNMENT, SEARCHING_FAILED, CANCELLED));
        // Tenant fallback (Requirement MT-9.1).
        m.put(AWAITING_ASSIGNMENT, EnumSet.of(PROVIDER_ASSIGNED, SEARCHING_FAILED, CANCELLED));
        m.put(PROVIDER_ASSIGNED, EnumSet.of(PROVIDER_ACCEPTED, AWAITING_ASSIGNMENT, CANCELLED));
        m.put(PROVIDER_ACCEPTED, EnumSet.of(PROVIDER_ON_THE_WAY, CANCELLED));
        m.put(PROVIDER_ON_THE_WAY, EnumSet.of(PROVIDER_ARRIVED, CANCELLED));
        m.put(PROVIDER_ARRIVED, EnumSet.of(JOB_STARTED));
        m.put(JOB_STARTED, EnumSet.of(JOB_PAUSED, ADDITIONAL_QUOTE_REQUIRED, JOB_COMPLETED));
        m.put(JOB_PAUSED, EnumSet.of(JOB_STARTED));
        m.put(ADDITIONAL_QUOTE_REQUIRED, EnumSet.of(CUSTOMER_APPROVAL_PENDING));
        m.put(CUSTOMER_APPROVAL_PENDING, EnumSet.of(JOB_STARTED, JOB_COMPLETED));
        m.put(JOB_COMPLETED, EnumSet.of(CUSTOMER_CONFIRMED, DISPUTED));
        m.put(CUSTOMER_CONFIRMED, EnumSet.of(PAYMENT_PENDING));
        m.put(PAYMENT_PENDING, EnumSet.of(PAYMENT_COMPLETED, DISPUTED));
        m.put(PAYMENT_COMPLETED, EnumSet.of(REFUNDED));
        m.put(DISPUTED, EnumSet.of(REFUNDED, PAYMENT_COMPLETED));
        m.put(SEARCHING_FAILED, EnumSet.noneOf(BookingStatus.class));
        m.put(REFUNDED, EnumSet.noneOf(BookingStatus.class));
        m.put(CANCELLED, EnumSet.noneOf(BookingStatus.class));
        return m;
    }

    @Test
    void permittedTargetsMatchTheRequirementMapForEveryState() {
        for (BookingStatus from : BookingStatus.values()) {
            assertThat(sm.permittedTargets(from))
                    .as("permitted targets from %s", from)
                    .containsExactlyInAnyOrderElementsOf(expected().get(from));
        }
    }

    @Test
    void everyPermittedTransitionIsAllowed() {
        expected().forEach((from, targets) ->
                targets.forEach(to -> assertThat(sm.isPermitted(from, to))
                        .as("permitted %s -> %s", from, to)
                        .isTrue()));
    }

    @Test
    void everyNonPermittedTransitionIsRejected() {
        Map<BookingStatus, Set<BookingStatus>> exp = expected();
        for (BookingStatus from : BookingStatus.values()) {
            for (BookingStatus to : BookingStatus.values()) {
                boolean allowed = exp.get(from).contains(to);
                assertThat(sm.isPermitted(from, to))
                        .as("%s -> %s should be %s", from, to, allowed ? "allowed" : "rejected")
                        .isEqualTo(allowed);
            }
        }
    }

    @Test
    void selfTransitionsAreNeverAllowed() {
        for (BookingStatus s : BookingStatus.values()) {
            assertThat(sm.isPermitted(s, s)).as("self-transition %s", s).isFalse();
        }
    }

    @Test
    void terminalStatesHaveNoOutgoingTransitions() {
        assertThat(sm.isTerminal(SEARCHING_FAILED)).isTrue();
        assertThat(sm.isTerminal(REFUNDED)).isTrue();
        assertThat(sm.isTerminal(CANCELLED)).isTrue();
        assertThat(sm.isTerminal(CREATED)).isFalse();
        assertThat(sm.isTerminal(JOB_STARTED)).isFalse();
    }

    @Test
    void awaitingAssignmentIsEnteredOnlyFromSearchingOrADeclinedAssignment() {
        // Property MT1: the queue is reached from automatic matching (fallback) or by the assigned
        // Provider's decline, never from CREATED, an accepted job or a terminal state.
        for (BookingStatus from : BookingStatus.values()) {
            boolean allowed = from == SEARCHING_PROVIDER || from == PROVIDER_ASSIGNED;
            assertThat(sm.isPermitted(from, AWAITING_ASSIGNMENT))
                    .as("%s -> AWAITING_ASSIGNMENT", from)
                    .isEqualTo(allowed);
        }
        // A queued booking cannot skip the Provider's confirmation (Requirement MT-6.1).
        assertThat(sm.isPermitted(AWAITING_ASSIGNMENT, PROVIDER_ACCEPTED)).isFalse();
        assertThat(sm.isTerminal(AWAITING_ASSIGNMENT)).isFalse();
    }

    @Test
    void nullStatesAreRejected() {
        assertThat(sm.isPermitted(null, CREATED)).isFalse();
        assertThat(sm.isPermitted(CREATED, null)).isFalse();
        assertThat(sm.isPermitted(null, null)).isFalse();
    }
}
