package com.homefix.booking.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
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
import com.homefix.booking.domain.BookingAuditRepository;
import com.homefix.booking.domain.BookingRepository;
import com.homefix.booking.domain.BookingStatus;

/**
 * Settles additional quotes the customer never answered (Requirement 9.9): every minute, each
 * booking that entered CUSTOMER_APPROVAL_PENDING longer than
 * {@code homefix.booking.additional-quote-timeout} ago (default 60 minutes) is completed at its
 * original price by {@link JobExecutionService#autoResolveApprovalTimeout}, with the system recorded
 * as the actor. That method existed from the start but nothing called it, so an unanswered quote
 * held the job open indefinitely.
 *
 * <p>The time the booking entered the state comes from its audit trail; the most recent entry
 * counts, since a second parts request after an approval starts a new wait. Each booking is settled
 * in its own short transaction that re-reads it and re-checks both the state and the deadline, and
 * the booking's optimistic lock settles a race with the customer's own answer or another instance's
 * sweep: one commits, the other's {@link OptimisticLockingFailureException} is expected and skipped.
 * A failure on one booking never stops the batch. Same shape as {@link AssignmentTimeoutSweeper}.
 */
@Component
public class QuoteApprovalTimeoutSweeper {

    private static final Logger log = LoggerFactory.getLogger(QuoteApprovalTimeoutSweeper.class);

    /** Most bookings settled per pass; a backlog drains over successive minutes. */
    static final int BATCH_SIZE = 100;

    private final BookingRepository bookingRepository;
    private final BookingAuditRepository auditRepository;
    private final JobExecutionService jobExecution;
    private final TransactionOperations transactions;
    private final BookingProperties properties;
    private final Clock clock;

    public QuoteApprovalTimeoutSweeper(BookingRepository bookingRepository,
                                       BookingAuditRepository auditRepository,
                                       JobExecutionService jobExecution,
                                       TransactionOperations transactions,
                                       BookingProperties properties,
                                       Clock clock) {
        this.bookingRepository = bookingRepository;
        this.auditRepository = auditRepository;
        this.jobExecution = jobExecution;
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
            log.error("Quote approval timeout sweep failed: {}", e.getMessage(), e);
        }
    }

    /**
     * Completes every overdue unanswered quote at the original price (up to {@value #BATCH_SIZE}).
     *
     * @return how many bookings this call completed
     */
    public int sweep() {
        Duration timeout = properties.getAdditionalQuoteTimeout();
        Instant cutoff = Instant.now(clock).minus(timeout);
        List<UUID> overdue = bookingRepository
                .findCustomerApprovalPendingSince(cutoff, PageRequest.of(0, BATCH_SIZE))
                .stream()
                .map(Booking::getId)
                .toList();
        int completed = 0;
        for (UUID bookingId : overdue) {
            try {
                Boolean done = transactions.execute(tx -> resolve(bookingId));
                if (Boolean.TRUE.equals(done)) {
                    completed++;
                }
            } catch (OptimisticLockingFailureException e) {
                log.debug("Booking {} changed while auto-resolving its quote (the customer answered or "
                        + "another instance won); skipping", bookingId);
            } catch (RuntimeException e) {
                log.warn("Could not auto-resolve the quote of booking {}: {}", bookingId, e.getMessage());
            }
        }
        if (completed > 0) {
            log.info("Quote approval timeout: {} booking(s) completed at the original price", completed);
        }
        return completed;
    }

    private boolean resolve(UUID bookingId) {
        Booking booking = bookingRepository.findById(bookingId).orElse(null);
        if (booking == null || booking.getStatus() != BookingStatus.CUSTOMER_APPROVAL_PENDING) {
            return false; // answered, or settled by another instance meanwhile
        }
        Instant pendingSince = auditRepository
                .lastEnteredAt(bookingId, BookingStatus.CUSTOMER_APPROVAL_PENDING)
                .orElse(null);
        if (pendingSince == null) {
            return false;
        }
        boolean resolved = jobExecution.autoResolveApprovalTimeout(booking.getReference(), pendingSince);
        if (resolved) {
            // Flush inside the transaction so a concurrent commit surfaces here as an optimistic-lock
            // failure rather than at commit, where it is reported the same way.
            bookingRepository.saveAndFlush(booking);
        }
        return resolved;
    }
}
