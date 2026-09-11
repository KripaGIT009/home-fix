package com.homefix.booking.service;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.homefix.booking.domain.Booking;
import com.homefix.booking.domain.BookingRepository;
import com.homefix.booking.domain.BookingStatus;


/**
 * Applies the outcome of a dispatch attempt to a booking (Requirements 8.6, 8.9).
 *
 * <p>The Booking Service owns the state machine; the Dispatch Engine decides <em>who</em> takes the
 * job and then asks for the transition. Both entry points are addressed by booking id rather than by
 * the customer-facing reference, because the Dispatch Engine only ever sees the id from the
 * {@code BookingCreated} event.
 *
 * <p>Acceptance deliberately performs <em>two</em> transitions. The state machine permits
 * {@code SEARCHING_PROVIDER -> PROVIDER_ASSIGNED -> PROVIDER_ACCEPTED} and has no direct edge from
 * SEARCHING_PROVIDER to PROVIDER_ACCEPTED, so the single-step request the Dispatch Engine used to
 * make was rejected with a 409 even when an endpoint existed to receive it. Both steps and the
 * provider assignment commit in one transaction, so a booking is never left resting in the
 * intermediate PROVIDER_ASSIGNED state by a partial failure.
 *
 * <p>Assigning {@code providerId} here is the only place it is ever set. Without it every downstream
 * provider event carried a null provider.
 */
@Service
public class DispatchOutcomeService {

    private static final Logger log = LoggerFactory.getLogger(DispatchOutcomeService.class);

    /** Audit actor for transitions the Dispatch Engine requests. */
    private static final String DISPATCH_ACTOR_ROLE = "dispatch-engine";

    private final BookingRepository bookingRepository;
    private final BookingTransitionService transitionService;

    public DispatchOutcomeService(BookingRepository bookingRepository,
                                  BookingTransitionService transitionService) {
        this.bookingRepository = bookingRepository;
        this.transitionService = transitionService;
    }

    /**
     * Records that a provider accepted the job: assigns the provider and walks the booking to
     * PROVIDER_ACCEPTED.
     *
     * <p>Idempotent for the same provider. A redelivered dispatch callback for a booking already
     * accepted by that provider is a no-op rather than a 409, because the Dispatch Engine retries
     * this call through its resilience stack and must be able to do so safely. A different provider
     * claiming an already-accepted booking is still rejected.
     *
     * @throws BookingException 404 when the booking does not exist, 409 when the transition is not
     *                          legal from the current state or another provider already holds it
     */
    @Transactional
    public Booking markProviderAccepted(UUID bookingId, UUID providerId) {
        if (providerId == null) {
            throw BookingException.validation("providerId is required");
        }
        Booking booking = requireBooking(bookingId);

        if (booking.getStatus() == BookingStatus.PROVIDER_ACCEPTED) {
            if (providerId.equals(booking.getProviderId())) {
                log.debug("Booking {} already accepted by provider {}; treating as a retry",
                        bookingId, providerId);
                return booking;
            }
            log.warn("Provider {} tried to accept booking {}, already held by provider {}",
                    providerId, bookingId, booking.getProviderId());
            throw new InvalidTransitionException(bookingId,
                    BookingStatus.PROVIDER_ACCEPTED, BookingStatus.PROVIDER_ACCEPTED);
        }

        booking.setProviderId(providerId);
        transitionService.transition(booking, BookingStatus.PROVIDER_ASSIGNED,
                dispatchActor(providerId), "Dispatch Engine assigned provider " + providerId);
        Booking accepted = transitionService.transition(booking, BookingStatus.PROVIDER_ACCEPTED,
                dispatchActor(providerId), "Provider accepted the job offer");
        log.info("Booking {} assigned to and accepted by provider {}", bookingId, providerId);
        return accepted;
    }

    /**
     * Records that dispatch exhausted every candidate and radius cycle without an acceptance.
     *
     * <p>Idempotent: a booking already in SEARCHING_FAILED is returned unchanged.
     *
     * @throws BookingException 404 when the booking does not exist, 409 when SEARCHING_FAILED is not
     *                          legal from the current state
     */
    @Transactional
    public Booking markSearchingFailed(UUID bookingId) {
        Booking booking = requireBooking(bookingId);
        if (booking.getStatus() == BookingStatus.SEARCHING_FAILED) {
            log.debug("Booking {} already marked SEARCHING_FAILED; treating as a retry", bookingId);
            return booking;
        }
        Booking failed = transitionService.transition(booking, BookingStatus.SEARCHING_FAILED,
                Actor.system(), "Dispatch Engine found no available provider");
        log.warn("Booking {} marked SEARCHING_FAILED: no provider accepted", bookingId);
        return failed;
    }

    private Actor dispatchActor(UUID providerId) {
        return Actor.user(providerId, DISPATCH_ACTOR_ROLE);
    }

    private Booking requireBooking(UUID bookingId) {
        return bookingRepository.findById(bookingId)
                .orElseThrow(() -> BookingException.notFound(String.valueOf(bookingId)));
    }
}
