package com.homefix.booking.domain;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

import static com.homefix.booking.domain.BookingStatus.ADDITIONAL_QUOTE_REQUIRED;
import static com.homefix.booking.domain.BookingStatus.AWAITING_ASSIGNMENT;
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
import static com.homefix.booking.domain.BookingStatus.PROVIDER_ON_THE_WAY;
import static com.homefix.booking.domain.BookingStatus.REFUNDED;
import static com.homefix.booking.domain.BookingStatus.SEARCHING_FAILED;
import static com.homefix.booking.domain.BookingStatus.SEARCHING_PROVIDER;

/**
 * Reusable, stateless component that encodes the Booking lifecycle permitted-transition map
 * from Requirement 9.1 and Property 8.
 *
 * <p>It is the single source of truth for which transitions are legal. The
 * {@code BookingService} (Task 14) and the job-execution transitions built on top of it
 * (Task 15) both consult this component rather than duplicating the map.
 *
 * <p>Permitted transitions (Requirement 9.1):
 * <pre>
 *   CREATED                    -> SEARCHING_PROVIDER | CANCELLED
 *   SEARCHING_PROVIDER         -> PROVIDER_ASSIGNED | AWAITING_ASSIGNMENT | SEARCHING_FAILED | CANCELLED
 *   AWAITING_ASSIGNMENT        -> PROVIDER_ASSIGNED | SEARCHING_FAILED | CANCELLED
 *   PROVIDER_ASSIGNED          -> PROVIDER_ACCEPTED | AWAITING_ASSIGNMENT | CANCELLED
 *   PROVIDER_ACCEPTED          -> PROVIDER_ON_THE_WAY | CANCELLED
 *   PROVIDER_ON_THE_WAY        -> PROVIDER_ARRIVED | CANCELLED
 *   PROVIDER_ARRIVED           -> JOB_STARTED
 *   JOB_STARTED                -> JOB_PAUSED | ADDITIONAL_QUOTE_REQUIRED | JOB_COMPLETED
 *   JOB_PAUSED                 -> JOB_STARTED
 *   ADDITIONAL_QUOTE_REQUIRED  -> CUSTOMER_APPROVAL_PENDING
 *   CUSTOMER_APPROVAL_PENDING  -> JOB_STARTED | JOB_COMPLETED
 *   JOB_COMPLETED              -> CUSTOMER_CONFIRMED | DISPUTED
 *   CUSTOMER_CONFIRMED         -> PAYMENT_PENDING
 *   PAYMENT_PENDING            -> PAYMENT_COMPLETED | DISPUTED
 *   PAYMENT_COMPLETED          -> REFUNDED
 *   DISPUTED                   -> REFUNDED | PAYMENT_COMPLETED
 *   Terminal (no outgoing):    SEARCHING_FAILED, REFUNDED, CANCELLED
 * </pre>
 *
 * <p>The {@code AWAITING_ASSIGNMENT} edges are the Tenant fallback (Requirement MT-9.1): a booking
 * nobody accepted automatically is queued for the covering Tenants instead of failing, a Tenant
 * assignment moves it to PROVIDER_ASSIGNED, and the assigned Provider's decline returns it to the
 * queue. Which caller may take each edge (fallback only after dispatch failed, decline only for a
 * queued booking) is the services' rule; this map only says the edge exists.
 *
 * <p>{@code CREATED -> CANCELLED} is not in Requirement 9.1's list, but Requirement 9.16 names
 * CREATED among the states a booking may be cancelled from at no fee; without the edge an
 * unconfirmed booking could never be withdrawn.
 */
public final class BookingStateMachine {

    private static final Map<BookingStatus, Set<BookingStatus>> TRANSITIONS = buildTransitions();

    private static Map<BookingStatus, Set<BookingStatus>> buildTransitions() {
        Map<BookingStatus, Set<BookingStatus>> map = new EnumMap<>(BookingStatus.class);
        map.put(CREATED, EnumSet.of(SEARCHING_PROVIDER, CANCELLED));
        map.put(SEARCHING_PROVIDER, EnumSet.of(PROVIDER_ASSIGNED, AWAITING_ASSIGNMENT, SEARCHING_FAILED, CANCELLED));
        map.put(AWAITING_ASSIGNMENT, EnumSet.of(PROVIDER_ASSIGNED, SEARCHING_FAILED, CANCELLED));
        map.put(PROVIDER_ASSIGNED, EnumSet.of(PROVIDER_ACCEPTED, AWAITING_ASSIGNMENT, CANCELLED));
        map.put(PROVIDER_ACCEPTED, EnumSet.of(PROVIDER_ON_THE_WAY, CANCELLED));
        map.put(PROVIDER_ON_THE_WAY, EnumSet.of(PROVIDER_ARRIVED, CANCELLED));
        map.put(PROVIDER_ARRIVED, EnumSet.of(JOB_STARTED));
        map.put(JOB_STARTED, EnumSet.of(JOB_PAUSED, ADDITIONAL_QUOTE_REQUIRED, JOB_COMPLETED));
        map.put(JOB_PAUSED, EnumSet.of(JOB_STARTED));
        map.put(ADDITIONAL_QUOTE_REQUIRED, EnumSet.of(CUSTOMER_APPROVAL_PENDING));
        map.put(CUSTOMER_APPROVAL_PENDING, EnumSet.of(JOB_STARTED, JOB_COMPLETED));
        map.put(JOB_COMPLETED, EnumSet.of(CUSTOMER_CONFIRMED, DISPUTED));
        map.put(CUSTOMER_CONFIRMED, EnumSet.of(PAYMENT_PENDING));
        map.put(PAYMENT_PENDING, EnumSet.of(PAYMENT_COMPLETED, DISPUTED));
        map.put(PAYMENT_COMPLETED, EnumSet.of(REFUNDED));
        map.put(DISPUTED, EnumSet.of(REFUNDED, PAYMENT_COMPLETED));
        // Terminal states have no outgoing transitions.
        map.put(SEARCHING_FAILED, EnumSet.noneOf(BookingStatus.class));
        map.put(REFUNDED, EnumSet.noneOf(BookingStatus.class));
        map.put(CANCELLED, EnumSet.noneOf(BookingStatus.class));
        return Collections.unmodifiableMap(map);
    }

    /**
     * @return an unmodifiable view of the states {@code from} may legally transition to.
     */
    public Set<BookingStatus> permittedTargets(BookingStatus from) {
        return Collections.unmodifiableSet(TRANSITIONS.getOrDefault(from, EnumSet.noneOf(BookingStatus.class)));
    }

    /**
     * @return {@code true} iff transitioning {@code from -> to} is permitted by Requirement 9.1.
     */
    public boolean isPermitted(BookingStatus from, BookingStatus to) {
        return from != null && to != null && permittedTargets(from).contains(to);
    }

    /**
     * @return {@code true} iff {@code state} is a terminal state (no outgoing transitions):
     *         SEARCHING_FAILED, REFUNDED, CANCELLED.
     */
    public boolean isTerminal(BookingStatus state) {
        return permittedTargets(state).isEmpty();
    }
}
