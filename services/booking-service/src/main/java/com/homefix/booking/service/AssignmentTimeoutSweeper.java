package com.homefix.booking.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

import com.homefix.booking.config.BookingProperties;
import com.homefix.booking.domain.Booking;
import com.homefix.booking.domain.BookingRepository;
import com.homefix.booking.domain.BookingStatus;

/**
 * Fails bookings that no Tenant got confirmed in time (Requirement MT-7, Property MT6): every minute,
 * each booking first queued longer than {@code homefix.booking.tenant-assignment-timeout} ago that
 * is still waiting moves to SEARCHING_FAILED through the transition service, so the customer is told
 * exactly as when dispatch fails a booking (the state-driven {@code BookingCancelled}, Requirement
 * MT-7.1). "Still waiting" covers both pre-acceptance states:
 * <ul>
 *   <li><b>AWAITING_ASSIGNMENT</b> — no Tenant assigned anyone: one audited step to
 *       SEARCHING_FAILED.</li>
 *   <li><b>PROVIDER_ASSIGNED</b> — a Tenant assigned a Provider who has not confirmed. The customer
 *       was promised an answer within the timeout, so an unanswered assignment must not hold the
 *       booking forever. The state machine has no direct edge to SEARCHING_FAILED, so the booking
 *       takes the existing ones: PROVIDER_ASSIGNED -> AWAITING_ASSIGNMENT (passed through, audited,
 *       as if the Provider had declined) -> SEARCHING_FAILED, in one transaction. The Provider stays
 *       on the booking, so the {@code BookingCancelled} names them and notification-service tells
 *       them the job is off ("you do not need to attend"); SEARCHING_FAILED is not an active state,
 *       so the job leaves their dashboard, and a late acceptance is refused as an illegal
 *       transition.</li>
 * </ul>
 *
 * <p>Only bookings that went through the Tenant fallback carry {@code queued_for_assignment_at}; the
 * Dispatch Engine's acceptance passes through PROVIDER_ASSIGNED without one and is never touched.
 * The clock runs from that first queue time, which a decline never resets, so a Provider declining
 * does not buy the booking more time (Requirement MT-7.2).
 *
 * <h2>More than one instance, and late answers (Requirement MT-7.3)</h2>
 * Each booking is failed in its own short transaction that re-reads it and checks it is still
 * waiting and still overdue. Two instances sweeping at once both find the booking, but the
 * booking's optimistic lock lets only one commit; the other's
 * {@link OptimisticLockingFailureException} is expected and skipped, and the next pass sees the
 * booking settled. The same lock settles a Provider's acceptance (or a Tenant's assignment) racing
 * the sweep: exactly one of them commits. A failure on one booking never stops the batch.
 */
@Component
public class AssignmentTimeoutSweeper {

    private static final Logger log = LoggerFactory.getLogger(AssignmentTimeoutSweeper.class);

    /** Most bookings failed per pass; a backlog drains over successive minutes. */
    static final int BATCH_SIZE = 100;

    /** The pre-acceptance states a fallback booking can be stuck in. */
    static final Set<BookingStatus> WAITING = EnumSet.of(
            BookingStatus.AWAITING_ASSIGNMENT, BookingStatus.PROVIDER_ASSIGNED);

    private final BookingRepository bookingRepository;
    private final BookingTransitionService transitionService;
    private final TransactionOperations transactions;
    private final BookingProperties properties;
    private final Clock clock;

    public AssignmentTimeoutSweeper(BookingRepository bookingRepository,
                                    BookingTransitionService transitionService,
                                    TransactionOperations transactions,
                                    BookingProperties properties,
                                    Clock clock) {
        this.bookingRepository = bookingRepository;
        this.transitionService = transitionService;
        this.transactions = transactions;
        this.properties = properties;
        this.clock = clock;
    }

    /** The scheduled pass: once a minute, first run a minute after start-up. */
    @Scheduled(fixedDelayString = "PT60S", initialDelayString = "PT60S")
    public void sweepScheduled() {
        try {
            sweep();
        } catch (RuntimeException e) {
            // A scheduled method that throws is simply retried next minute; log it so it is seen.
            log.error("Assignment timeout sweep failed: {}", e.getMessage(), e);
        }
    }

    /**
     * Fails every overdue fallback booking still waiting for a confirmed Provider (up to
     * {@value #BATCH_SIZE}).
     *
     * @return how many bookings this call moved to SEARCHING_FAILED
     */
    public int sweep() {
        Duration timeout = properties.getTenantAssignmentTimeout();
        Instant cutoff = Instant.now(clock).minus(timeout);
        List<UUID> overdue = bookingRepository
                .findByStatusInAndQueuedForAssignmentAtBeforeOrderByQueuedForAssignmentAtAsc(
                        WAITING, cutoff, PageRequest.of(0, BATCH_SIZE))
                .stream()
                .map(Booking::getId)
                .toList();
        int failed = 0;
        for (UUID bookingId : overdue) {
            try {
                Boolean done = transactions.execute(tx -> expire(bookingId, cutoff, timeout));
                if (Boolean.TRUE.equals(done)) {
                    failed++;
                }
            } catch (OptimisticLockingFailureException e) {
                log.debug("Booking {} changed while expiring its assignment (another instance, an "
                        + "assignment or an acceptance won); skipping", bookingId);
            } catch (RuntimeException e) {
                log.warn("Could not expire the assignment of booking {}: {}", bookingId, e.getMessage());
            }
        }
        if (failed > 0) {
            log.info("Assignment timeout: {} booking(s) moved to SEARCHING_FAILED", failed);
        }
        return failed;
    }

    private boolean expire(UUID bookingId, Instant cutoff, Duration timeout) {
        Booking booking = bookingRepository.findById(bookingId).orElse(null);
        if (booking == null
                || !WAITING.contains(booking.getStatus())
                || booking.getQueuedForAssignmentAt() == null
                || !booking.getQueuedForAssignmentAt().isBefore(cutoff)) {
            return false; // accepted, cancelled or swept meanwhile
        }
        if (booking.getStatus() == BookingStatus.PROVIDER_ASSIGNED) {
            transitionService.transitionPassingThrough(booking, BookingStatus.AWAITING_ASSIGNMENT,
                    Actor.system(), "Assigned provider " + booking.getProviderId()
                            + " did not confirm before the assignment deadline");
            transitionService.transition(booking, BookingStatus.SEARCHING_FAILED, Actor.system(),
                    "Assigned provider did not confirm within " + timeout + "; customer notified");
        } else {
            transitionService.transition(booking, BookingStatus.SEARCHING_FAILED, Actor.system(),
                    "No partner assigned a provider within " + timeout + "; customer notified");
        }
        // Flush inside the transaction so a concurrent sweeper's commit surfaces here as an
        // optimistic-lock failure rather than at commit, where it is reported the same way.
        bookingRepository.saveAndFlush(booking);
        log.warn("Booking {} marked SEARCHING_FAILED: not assigned and confirmed within {}", bookingId, timeout);
        return true;
    }
}
