package com.homefix.booking.service;

import java.util.Set;
import java.util.UUID;

import com.homefix.booking.domain.Booking;
import com.homefix.booking.domain.BookingRepository;

/**
 * Who may drive a booking through a {@code /bookings/{key}} command: the assigned provider for job
 * milestones, the customer for their own decisions, either of them to cancel, staff for all.
 *
 * <p>{@code RbacEnforcementFilter} only knows the caller's role; whether they may act on
 * <em>this</em> booking depends on its customer and provider. Without this check any signed-in
 * user who learned a reference — every provider sees one in an offer — could start, complete or
 * cancel someone else's booking. A refused caller gets the same 404 as for a missing booking, as on
 * the read side, so a reference cannot be probed for existence.
 */
public final class BookingAccess {

    /** Roles that may act on any booking — the read side's staff set. */
    static final Set<String> STAFF_ROLES = Set.of(
            "ADMIN", "SUPER_ADMIN", "FINANCE_ADMIN", "SUPPORT_AGENT", "DISPATCHER");

    private BookingAccess() {
    }

    /** The booking, if {@code actor} is its assigned provider, staff or the system. */
    public static Booking requireForProvider(BookingRepository repository, String key, Actor actor) {
        return repository.findByKey(key)
                .filter(b -> mayAct(actor, b.getProviderId()))
                .orElseThrow(() -> BookingException.notFound(key));
    }

    /** The booking, if {@code actor} is its customer, staff or the system. */
    public static Booking requireForCustomer(BookingRepository repository, String key, Actor actor) {
        return repository.findByKey(key)
                .filter(b -> mayAct(actor, b.getCustomerId()))
                .orElseThrow(() -> BookingException.notFound(key));
    }

    /** The booking, if {@code actor} is its customer or assigned provider, staff or the system. */
    public static Booking requireForParticipant(BookingRepository repository, String key, Actor actor) {
        return repository.findByKey(key)
                .filter(b -> mayAct(actor, b.getCustomerId()) || mayAct(actor, b.getProviderId()))
                .orElseThrow(() -> BookingException.notFound(key));
    }

    private static boolean mayAct(Actor actor, UUID owner) {
        return actor.id() == null // system-initiated: timers, sweepers
                || STAFF_ROLES.contains(actor.role())
                || actor.id().equals(owner);
    }
}
