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
 *       (Property 9);</li>
 *   <li>the {@link BookingLifecycleEventPublisher} writes the outbox row for state-driven events
 *       (BookingCancelled, ProviderAssigned) in the same transaction (Requirement 22.1, 22.2), so
 *       no path into those states can skip the event.</li>
 * </ol>
 *
 * <p>The one exception is {@link #transitionPassingThrough}, for an intermediate state the caller
 * leaves again in the same transaction: it is validated and audited like any other transition,
 * but announces nothing, because no one can observe the booking resting there.
 */
@Service
public class BookingTransitionService {

    private static final Logger log = LoggerFactory.getLogger(BookingTransitionService.class);

    private final BookingStateMachine stateMachine;
    private final BookingAuditRepository auditRepository;
    private final BookingLifecycleEventPublisher lifecycleEvents;
    private final Clock clock;

    public BookingTransitionService(BookingStateMachine stateMachine,
                                    BookingAuditRepository auditRepository,
                                    BookingLifecycleEventPublisher lifecycleEvents,
                                    Clock clock) {
        this.stateMachine = stateMachine;
        this.auditRepository = auditRepository;
        this.lifecycleEvents = lifecycleEvents;
        this.clock = clock;
    }

    /**
     * Applies {@code target} to {@code booking}, validating against the state machine,
     * recording an audit entry and writing any state-driven outbox event.
     *
     * @throws InvalidTransitionException if the transition is not permitted (Requirement 9.2)
     */
    @Transactional
    public Booking transition(Booking booking, BookingStatus target, Actor actor, String reason) {
        return apply(booking, target, actor, reason, true, null);
    }

    /**
     * {@link #transition} for a step a Tenant causes, so the state-driven event can name it: a
     * Tenant assignment's {@code ProviderAssigned} carries the Tenant's name (Requirement MT-5.3).
     *
     * @param tenantName the Tenant's display name, or null when it is unknown
     * @throws InvalidTransitionException if the transition is not permitted (Requirement 9.2)
     */
    @Transactional
    public Booking transitionForTenant(Booking booking, BookingStatus target, Actor actor, String reason,
                                       String tenantName) {
        return apply(booking, target, actor, reason, true, tenantName);
    }

    /**
     * Applies an intermediate {@code target} that the caller moves past before its transaction
     * commits. Validation and the audit entry are exactly as for {@link #transition}, keeping the
     * audit chain contiguous; the state-driven outbox event is not written, since the booking is
     * never observed in {@code target}.
     *
     * <p>Used by the Dispatch Engine's acceptance callback, which walks
     * SEARCHING_PROVIDER -> PROVIDER_ASSIGNED -> PROVIDER_ACCEPTED in one transaction: announcing
     * ProviderAssigned there would tell the customer about an assignment immediately superseded
     * by the acceptance, and tell the provider about a job they have just accepted.
     *
     * @throws IllegalArgumentException   if {@code target} is terminal, since a booking cannot pass
     *                                    through a state with no way out
     * @throws InvalidTransitionException if the transition is not permitted (Requirement 9.2)
     */
    @Transactional
    public Booking transitionPassingThrough(Booking booking, BookingStatus target, Actor actor, String reason) {
        if (stateMachine.isTerminal(target)) {
            throw new IllegalArgumentException("Cannot pass through terminal state " + target);
        }
        return apply(booking, target, actor, reason, false, null);
    }

    private Booking apply(Booking booking, BookingStatus target, Actor actor, String reason,
                          boolean publishLifecycleEvent, String tenantName) {
        BookingStatus from = booking.getStatus();
        if (!stateMachine.isPermitted(from, target)) {
            // Requirement 9.2: log the rejected attempt with all identifying detail.
            log.warn("Rejected illegal booking transition bookingId={} from={} to={} actorId={} actorRole={}",
                    booking.getId(), from, target, actor.id(), actor.role());
            throw new InvalidTransitionException(booking.getId(), from, target);
        }
        booking.applyStatus(target);
        recordAudit(booking, from, target, actor, reason);
        if (publishLifecycleEvent) {
            lifecycleEvents.onTransition(booking, from, target, actor, reason, tenantName);
        }
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
