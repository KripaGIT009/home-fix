package com.homefix.booking.service;

import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.homefix.booking.domain.Booking;
import com.homefix.booking.domain.BookingRepository;
import com.homefix.booking.domain.BookingStatus;

/**
 * The booking's side of paying for a finished job (Requirements 12.1, 12.6, 9.1).
 *
 * <p>The Payment Service owns the money; this service owns the state machine. The two meet in three
 * places, all addressed by booking id because that is what the Payment Service holds:
 * <ul>
 *   <li>{@link #paymentFacts} — what the booking costs and who is paid, so the Payment Service
 *       charges the booking's own amount and provider instead of trusting the client's;</li>
 *   <li>{@link #markPaymentPending} — the customer has asked to pay: JOB_COMPLETED is moved
 *       through CUSTOMER_CONFIRMED to PAYMENT_PENDING before any money moves;</li>
 *   <li>{@link #markPaymentCompleted} — the {@code PaymentCompleted} event has arrived: the booking
 *       is settled at PAYMENT_COMPLETED.</li>
 * </ul>
 *
 * <p>Every step goes through {@link BookingTransitionService}, so the state machine, the audit chain
 * and the lifecycle events stay the single enforcement point. A state the booking only passes through
 * in the same transaction (CUSTOMER_CONFIRMED on the way to PAYMENT_PENDING, and PAYMENT_PENDING when
 * the completion event overtakes the pending call) is applied with
 * {@link BookingTransitionService#transitionPassingThrough}: audited, but announced to no one.
 *
 * <h2>Concurrent updates</h2>
 * {@code Booking} carries a {@code @Version}, so the pending call and the completion consumer cannot
 * silently overwrite each other; the loser of a race fails its commit instead. The two recover
 * differently:
 * <ul>
 *   <li>{@link #markPaymentPending} retries its own transaction against the fresh row. Two pending
 *       calls racing (a double-tapped Pay button) then end with the second seeing PAYMENT_PENDING and
 *       answering 200; losing to the completion event ends in a 409, which is the truth.</li>
 *   <li>{@link #markPaymentCompleted} joins the consumer's transaction, so it cannot retry inside it.
 *       The failure propagates, the consumer's transaction (including its dedup row) rolls back, and
 *       the shared {@code IdempotentKafkaConsumer} redelivers the event, which then sees the winner's
 *       state.</li>
 * </ul>
 */
@Service
public class BookingPaymentService {

    private static final Logger log = LoggerFactory.getLogger(BookingPaymentService.class);

    /** Role recorded on the audit rows of a customer's request to pay. */
    static final String CUSTOMER_ROLE = "CUSTOMER";

    /** Attempts {@link #markPaymentPending} makes when it loses an optimistic-lock race. */
    static final int PENDING_ATTEMPTS = 3;

    /** States from which a payment may still be started (Requirement 12.1). */
    public static final Set<BookingStatus> PAYABLE_STATUSES = Set.of(
            BookingStatus.JOB_COMPLETED,
            BookingStatus.CUSTOMER_CONFIRMED,
            BookingStatus.PAYMENT_PENDING);

    /** What the completion consumer did with a {@code PaymentCompleted} event. */
    public enum CompletionOutcome {
        /** The booking moved to PAYMENT_COMPLETED. */
        COMPLETED,
        /** The booking was already PAYMENT_COMPLETED; nothing changed. */
        ALREADY_COMPLETED,
        /** The booking is in a state a payment cannot settle (cancelled, disputed, refunded...). */
        NOT_PAYABLE,
        /** No booking has that id. */
        UNKNOWN_BOOKING
    }

    private final BookingRepository bookingRepository;
    private final BookingTransitionService transitionService;
    private final TransactionTemplate transactionTemplate;

    public BookingPaymentService(BookingRepository bookingRepository,
                                 BookingTransitionService transitionService,
                                 PlatformTransactionManager transactionManager) {
        this.bookingRepository = bookingRepository;
        this.transitionService = transitionService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * The booking whose payment facts the Payment Service reads.
     *
     * @throws BookingException 404 BOOKING_NOT_FOUND when no booking has that id
     */
    public Booking paymentFacts(UUID bookingId) {
        return bookingRepository.findById(bookingId)
                .orElseThrow(() -> BookingException.notFound(String.valueOf(bookingId)));
    }

    /**
     * Records that {@code customerId} has asked to pay for their booking: JOB_COMPLETED ->
     * CUSTOMER_CONFIRMED -> PAYMENT_PENDING, or CUSTOMER_CONFIRMED -> PAYMENT_PENDING when the job was
     * already confirmed. Both steps are audited with the customer as the actor.
     *
     * <p>Idempotent: a booking already PAYMENT_PENDING is returned unchanged, because the Payment
     * Service calls this on every attempt, including a retry after a failed charge.
     *
     * @throws BookingException 404 BOOKING_NOT_FOUND when the booking does not exist or is not this
     *                          customer's (the same answer, so an id cannot be probed); 409
     *                          BOOKING_NOT_PAYABLE from any state other than the three payable ones
     */
    public Booking markPaymentPending(UUID bookingId, UUID customerId) {
        if (customerId == null) {
            throw BookingException.validation("customerId is required");
        }
        for (int attempt = 1; ; attempt++) {
            try {
                return transactionTemplate.execute(status -> applyPaymentPending(bookingId, customerId));
            } catch (OptimisticLockingFailureException concurrentUpdate) {
                if (attempt >= PENDING_ATTEMPTS) {
                    throw concurrentUpdate;
                }
                log.info("Booking {} changed while being marked PAYMENT_PENDING (attempt {}/{}); retrying",
                        bookingId, attempt, PENDING_ATTEMPTS);
            }
        }
    }

    private Booking applyPaymentPending(UUID bookingId, UUID customerId) {
        Actor customer = Actor.user(customerId, CUSTOMER_ROLE);
        // Same ownership rule as the customer's own commands: anyone else gets the 404 of a missing
        // booking. The actor is never staff here, so only the booking's customer passes.
        Booking booking = BookingAccess.requireForCustomer(bookingRepository, bookingId.toString(), customer);
        switch (booking.getStatus()) {
            case PAYMENT_PENDING -> {
                log.debug("Booking {} already PAYMENT_PENDING; treating as a retry", bookingId);
                return booking;
            }
            case JOB_COMPLETED -> {
                transitionService.transitionPassingThrough(booking, BookingStatus.CUSTOMER_CONFIRMED,
                        customer, "Customer confirmed the completed job by paying");
                transitionService.transition(booking, BookingStatus.PAYMENT_PENDING,
                        customer, "Customer started payment");
            }
            case CUSTOMER_CONFIRMED -> transitionService.transition(booking, BookingStatus.PAYMENT_PENDING,
                    customer, "Customer started payment");
            default -> throw BookingException.notPayable(
                    "Booking " + booking.getReference() + " is " + booking.getStatus() + " and cannot be paid");
        }
        log.info("Booking {} is PAYMENT_PENDING for customer {}", bookingId, customerId);
        return booking;
    }

    /**
     * Settles a booking whose payment succeeded ({@code PaymentCompleted}, Requirement 12.6).
     *
     * <p>From PAYMENT_PENDING the booking moves straight to PAYMENT_COMPLETED; from JOB_COMPLETED or
     * CUSTOMER_CONFIRMED it passes through the intermediate states, all audited as system actions,
     * because the money has moved whether or not the pending call was recorded first.
     *
     * <p>Never throws for a booking it cannot settle: the event is a fact about money that has
     * already moved, so retrying or dead-lettering it cannot make a cancelled or disputed booking
     * payable. Those cases, and an unknown booking id, are logged at WARN and reported in the outcome
     * for the caller to acknowledge. (A {@code BookingException} here would also mark the consumer's shared
     * transaction rollback-only.) A lost optimistic-lock race does propagate, deliberately; see the
     * class Javadoc.
     */
    @Transactional
    public CompletionOutcome markPaymentCompleted(UUID bookingId, UUID paymentId) {
        Booking booking = bookingRepository.findById(bookingId).orElse(null);
        if (booking == null) {
            log.warn("PaymentCompleted for payment {} names no known booking {}; ignoring it", paymentId, bookingId);
            return CompletionOutcome.UNKNOWN_BOOKING;
        }
        Actor system = Actor.system();
        String reason = "Payment " + paymentId + " completed";
        switch (booking.getStatus()) {
            case PAYMENT_COMPLETED -> {
                return CompletionOutcome.ALREADY_COMPLETED;
            }
            case JOB_COMPLETED -> {
                transitionService.transitionPassingThrough(booking, BookingStatus.CUSTOMER_CONFIRMED, system, reason);
                transitionService.transitionPassingThrough(booking, BookingStatus.PAYMENT_PENDING, system, reason);
            }
            case CUSTOMER_CONFIRMED ->
                transitionService.transitionPassingThrough(booking, BookingStatus.PAYMENT_PENDING, system, reason);
            case PAYMENT_PENDING -> {
                // Straight to the final step below.
            }
            default -> {
                log.warn("PaymentCompleted for payment {} ignored: booking {} is {}, which a payment cannot settle",
                        paymentId, bookingId, booking.getStatus());
                return CompletionOutcome.NOT_PAYABLE;
            }
        }
        transitionService.transition(booking, BookingStatus.PAYMENT_COMPLETED, system, reason);
        log.info("Booking {} is PAYMENT_COMPLETED by payment {}", bookingId, paymentId);
        return CompletionOutcome.COMPLETED;
    }
}
