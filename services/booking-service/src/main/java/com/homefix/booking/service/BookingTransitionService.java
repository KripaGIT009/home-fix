package com.homefix.booking.service;

import java.time.Clock;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.homefix.booking.domain.Booking;
import com.homefix.booking.domain.BookingAudit;
import com.homefix.booking.domain.BookingAuditRepository;
import com.homefix.booking.domain.BookingStateMachine;
import com.homefix.booking.domain.BookingStatus;

/**
 * Central, reusable applier for booking state transitions (Requirement 9.1, 9.2, 9.15;
 * Property 8, Property 9).
 *
 * <p>Every transition in the platform — creation, dispatch progression, job-execution
 * milestones (Task 15), cancellation, payment — funnels through {@link #transition} so there
 * is a single enforcement point:
 * <ol>
 *   <li>the {@link BookingStateMachine} validates the transition is permitted;</li>
 *   <li>if not, the attempt is logged (booking id, source, target, actor) and an
 *       {@link InvalidTransitionException} (mapped to 409) is thrown — no state change and no
 *       audit row (Property 8);</li>
 *   <li>if permitted, the booking status is updated and <em>exactly one</em>
 *       {@link BookingAudit} row is written, keeping the audit trail a contiguous chain
 *       (Property 9).</li>
 * </ol>
 */
@Service
public class BookingTransitionService {

    private static final Logger log = LoggerFactory.getLogger(BookingTransitionService.class);

    private final BookingStateMachine stateMachine;
    private final BookingAuditRepository auditRepository;
    private final Clock clock;

    public BookingTransitionService(BookingStateMachine stateMachine,
                                    BookingAuditRepository auditRepository,
                                    Clock clock) {
        this.stateMachine = stateMachine;
        this.auditRepository = auditRepository;
        this.clock = clock;
    }

    /**
     * Applies {@code target} to {@code booking}, validating against the state machine and
     * recording an audit entry.
     *
     * @throws InvalidTransitionException if the transition is not permitted (Requirement 9.2)
     */
    @Transactional
    public Booking transition(Booking booking, BookingStatus target, Actor actor, String reason) {
        BookingStatus from = booking.getStatus();
        if (!stateMachine.isPermitted(from, target)) {
            // Requirement 9.2: log the rejected attempt with all identifying detail.
            log.warn("Rejected illegal booking transition bookingId={} from={} to={} actorId={} actorRole={}",
                    booking.getId(), from, target, actor.id(), actor.role());
            throw new InvalidTransitionException(booking.getId(), from, target);
        }
        booking.applyStatus(target);
        recordAudit(booking, from, target, actor, reason);
        log.info("Applied booking transition bookingId={} from={} to={} actorRole={}",
                booking.getId(), from, target, actor.role());
        return booking;
    }

    /**
     * Records the opening audit entry for a freshly created booking (from_state = null,
     * to_state = CREATED), starting the contiguous chain (Property 9).
     */
    @Transactional
    public void recordCreation(Booking booking, Actor actor, String reason) {
        recordAudit(booking, null, booking.getStatus(), actor, reason);
    }

    private void recordAudit(Booking booking, BookingStatus from, BookingStatus to,
                             Actor actor, String reason) {
        Instant now = Instant.now(clock);
        auditRepository.save(BookingAudit.of(
                booking.getId(), from, to, actor.id(), actor.role(), now, reason));
    }
}
