package com.homefix.dispatch.service;

import com.homefix.dispatch.domain.BookingNotSearchableException;
import com.homefix.dispatch.port.AcceptanceLedger;
import com.homefix.dispatch.port.BookingTransitionPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Finishes provider acceptances that the dispatch thread could not (Requirement 8.6): every 30 s,
 * each {@link AcceptanceLedger} entry that is due is retried.
 *
 * <ul>
 *   <li>If the Booking Service has not yet confirmed the transition, it is asked again. The request
 *       is idempotent for the same provider, so asking twice is harmless.</li>
 *   <li>Once it has, the entry is announced: removed and {@code ProviderAccepted} written to the
 *       outbox in one transaction.</li>
 *   <li>A 409 or 404 ({@link BookingNotSearchableException}) means the booking will never be this
 *       provider's — typically the customer cancelled while the Booking Service was unreachable — so
 *       the entry is discarded without an event.</li>
 *   <li>Anything else is a transient failure: the entry is postponed with backoff and retried on a
 *       later pass, however long the outage lasts.</li>
 * </ul>
 *
 * <p>Several instances may run this at once. Each entry is handled independently, the Booking
 * Service call is idempotent, and {@link AcceptanceLedger#announce} writes the event only for the
 * runner that removed the entry, so a race costs a duplicate HTTP call at most, never a second
 * event. A failure on one entry never stops the batch.
 */
@Component
public class AcceptanceReconciler {

    private static final Logger log = LoggerFactory.getLogger(AcceptanceReconciler.class);

    /** Most acceptances retried per pass; a backlog drains over successive passes. */
    static final int BATCH_SIZE = 50;

    private final AcceptanceLedger ledger;
    private final BookingTransitionPort bookingTransition;

    public AcceptanceReconciler(AcceptanceLedger ledger, BookingTransitionPort bookingTransition) {
        this.ledger = ledger;
        this.bookingTransition = bookingTransition;
    }

    /** The scheduled pass: every 30 s, first run 30 s after start-up. */
    @Scheduled(fixedDelayString = "PT30S", initialDelayString = "PT30S")
    public void reconcileScheduled() {
        try {
            reconcile();
        } catch (RuntimeException e) {
            // Typically the database is unreachable; the next pass tries again.
            log.error("Acceptance reconciliation pass failed: {}", e.getMessage(), e);
        }
    }

    /**
     * Retries every due acceptance (up to {@value #BATCH_SIZE}).
     *
     * @return how many acceptances this call announced
     */
    public int reconcile() {
        int announced = 0;
        for (AcceptanceLedger.Entry entry : ledger.due(BATCH_SIZE)) {
            try {
                if (complete(entry)) {
                    announced++;
                }
            } catch (RuntimeException e) {
                log.warn("Acceptance of booking {} by provider {} is still outstanding (attempt {}): {}",
                        entry.bookingId(), entry.providerId(), entry.attempts() + 1, e.toString());
                postponeQuietly(entry, e);
            }
        }
        if (announced > 0) {
            log.info("Acceptance reconciliation: announced {} outstanding acceptance(s)", announced);
        }
        return announced;
    }

    /** @return whether this call wrote the event */
    private boolean complete(AcceptanceLedger.Entry entry) {
        if (entry.bookingAccepted()) {
            return announce(entry);
        }
        try {
            bookingTransition.markProviderAccepted(entry.bookingId(), entry.providerId());
        } catch (BookingNotSearchableException refused) {
            log.warn("Booking Service refused the outstanding acceptance of booking {} by provider {};"
                    + " dropping it without ProviderAccepted: {}",
                    entry.bookingId(), entry.providerId(), refused.getMessage());
            ledger.discard(entry.bookingId());
            return false;
        }
        try {
            return announce(entry);
        } catch (RuntimeException e) {
            // The booking is accepted now and may move on (on the way, started) before the next
            // pass, after which asking again would be refused and the event dropped. Remember it.
            markBookingAcceptedQuietly(ledger, entry.bookingId());
            throw e;
        }
    }

    private boolean announce(AcceptanceLedger.Entry entry) {
        boolean written = ledger.announce(entry.bookingId());
        if (written) {
            log.info("Booking {} accepted by provider {} (completed by reconciliation)",
                    entry.bookingId(), entry.providerId());
        }
        return written;
    }

    /**
     * Notes that the Booking Service has applied the transition, after the announcement failed.
     * Best effort: the announcement failing usually means the database is unreachable, and then this
     * write fails too; the next pass then asks the Booking Service again.
     */
    static void markBookingAcceptedQuietly(AcceptanceLedger ledger, UUID bookingId) {
        try {
            ledger.markBookingAccepted(bookingId);
        } catch (RuntimeException e) {
            log.debug("Could not note the Booking Service's acceptance of booking {}: {}", bookingId, e.toString());
        }
    }

    private void postponeQuietly(AcceptanceLedger.Entry entry, RuntimeException cause) {
        try {
            ledger.postpone(entry.bookingId(), cause.toString());
        } catch (RuntimeException e) {
            log.debug("Could not postpone the acceptance of booking {}: {}", entry.bookingId(), e.toString());
        }
    }
}
