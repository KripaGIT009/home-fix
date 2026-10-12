package com.homefix.dispatch.port;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Durable record of provider acceptances that have not yet been fully applied (Requirement 8.6).
 *
 * <p>An acceptance has two effects in two systems: the Booking Service's PROVIDER_ACCEPTED
 * transition (an HTTP call) and this service's {@code ProviderAccepted} event (an outbox row). No
 * transaction spans both, so writing them one after the other was a dual write: a failure between
 * them — the outbox database briefly unavailable, or the process stopping — left a booking accepted
 * with no event, and nothing ever wrote it. Chat never activated, and the customer was never told.
 *
 * <p>The ledger closes that gap by recording the acceptance <em>before</em> either effect:
 * <ol>
 *   <li>{@link #open} — the acceptance is committed locally;</li>
 *   <li>the Booking Service is asked for the transition (idempotent for the same provider);</li>
 *   <li>{@link #announce} — in <em>one</em> local transaction, the entry is removed and the
 *       {@code ProviderAccepted} outbox row written.</li>
 * </ol>
 * Whatever fails after step 1 leaves the entry in place, and the {@code AcceptanceReconciler}
 * repeats steps 2 and 3 until they succeed or the Booking Service definitively refuses (the booking
 * was cancelled meanwhile), in which case the entry is {@link #discard discarded} and no event is
 * written. Every acceptance the Booking Service applied is therefore announced at least once, and in
 * practice exactly once: {@link #announce} writes the event only if it is the call that removed the
 * entry, so two runners racing on the same entry cannot both publish.
 */
public interface AcceptanceLedger {

    /** One acceptance still to be applied. */
    record Entry(UUID bookingId,
                 UUID customerId,
                 UUID providerId,
                 Instant bookingCreatedAt,
                 boolean bookingAccepted,
                 int attempts) {
    }

    /**
     * Records that {@code providerId} accepted {@code bookingId}. The entry is not due for the
     * reconciler until a lease has passed, so the dispatch thread that opened it can finish it
     * without competition.
     *
     * @throws RuntimeException when it cannot be recorded; the caller must not go on to request the
     *                          transition, since nothing would then guarantee the event
     */
    void open(UUID bookingId, UUID customerId, UUID providerId, Instant bookingCreatedAt);

    /**
     * Notes that the Booking Service has applied the transition, so a later retry announces the
     * acceptance without asking again — by then the booking may have moved past PROVIDER_ACCEPTED,
     * and asking again would be refused.
     */
    void markBookingAccepted(UUID bookingId);

    /**
     * Removes the entry and writes {@code ProviderAccepted} to the outbox, atomically.
     *
     * @return {@code true} if this call wrote the event; {@code false} if there was no entry (another
     *         runner announced it first)
     */
    boolean announce(UUID bookingId);

    /** Drops the entry without an event: the Booking Service refused the acceptance for good. */
    void discard(UUID bookingId);

    /** Records a failed attempt and pushes the entry's next retry back with exponential backoff. */
    void postpone(UUID bookingId, String error);

    /** Entries whose next attempt is due, oldest first, at most {@code limit} of them. */
    List<Entry> due(int limit);
}
