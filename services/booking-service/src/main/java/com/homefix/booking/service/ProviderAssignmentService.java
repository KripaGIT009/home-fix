package com.homefix.booking.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;

import com.homefix.booking.domain.Booking;
import com.homefix.booking.domain.BookingRepository;
import com.homefix.booking.domain.BookingStatus;
import com.homefix.booking.event.ProviderAcceptedEvent;
import com.homefix.booking.tenant.TenantDirectoryPort;
import com.homefix.booking.tenant.TenantDirectoryPort.TenantMembership;
import com.homefix.booking.tenant.TenantDirectoryUnavailableException;
import com.homefix.shared.outbox.OutboxEventPublisher;

/**
 * The assigned Provider's answer to a Tenant assignment (Requirement MT-6): accept, and the job
 * proceeds exactly like an automatically matched one; decline, and it goes back to the assigning
 * Tenant's queue.
 *
 * <p><b>Who may answer.</b> Only the Provider the booking is assigned to (Requirement MT-6.3). This
 * is stricter than {@link BookingAccess#requireForProvider}, which also admits staff: the answer is
 * the Provider's consent (design D6), which nobody can give on their behalf. Everyone else gets the
 * same 404 as for a missing booking.
 *
 * <p><b>Acceptance.</b> PROVIDER_ASSIGNED -> PROVIDER_ACCEPTED, and a {@code ProviderAccepted}
 * outbox row with the Dispatch Engine's payload in the same transaction, so chat opens the channel
 * and notification tells the customer just as for an automatic acceptance (Requirement MT-6.1,
 * Property MT7). It is written here rather than by {@link BookingLifecycleEventPublisher} because
 * the automatic path enters PROVIDER_ACCEPTED too, and there the Dispatch Engine publishes it.
 *
 * <p><b>Still a member.</b> A Tenant may remove a Provider after assigning them a job. The booking
 * keeps its assignment (Requirement MT-3.3 leaves assigned bookings alone), but the Provider's
 * acceptance is checked against the Provider Service's current membership first: a Provider who is
 * no longer in the booking's Tenant gets 409 {@code PROVIDER_NOT_ASSIGNABLE} and the booking is
 * unchanged (they can still decline it back to the Tenant's queue, and the assignment deadline still
 * runs). The lookup is an HTTP call, so it runs before the short transaction that applies the
 * acceptance, as in {@link TenantBookingService#assign}; when the Provider Service cannot answer the
 * acceptance is refused with 503 rather than let through unchecked. A booking without a Tenant (an
 * automatically dispatched one, which never rests in PROVIDER_ASSIGNED) is not checked.
 *
 * <p><b>Decline.</b> PROVIDER_ASSIGNED -> AWAITING_ASSIGNMENT with the Provider cleared. The Tenant
 * and the original queue time stay, so the booking returns to that Tenant only and the assignment
 * timeout keeps running from when it was first queued (Requirement MT-6.2, MT-7.2). Only a booking
 * that came through the Tenant fallback can be declined back into the queue (Property MT1).
 */
@Service
public class ProviderAssignmentService {

    private static final Logger log = LoggerFactory.getLogger(ProviderAssignmentService.class);

    /** Audit role for the Provider's answer. */
    static final String PROVIDER_ROLE = "SERVICE_PROVIDER";

    private final BookingRepository bookingRepository;
    private final BookingTransitionService transitionService;
    private final OutboxEventPublisher outboxPublisher;
    private final TenantDirectoryPort tenantDirectory;
    private final TransactionOperations transactions;
    private final Clock clock;

    public ProviderAssignmentService(BookingRepository bookingRepository,
                                     BookingTransitionService transitionService,
                                     OutboxEventPublisher outboxPublisher,
                                     TenantDirectoryPort tenantDirectory,
                                     TransactionOperations transactions,
                                     Clock clock) {
        this.bookingRepository = bookingRepository;
        this.transitionService = transitionService;
        this.outboxPublisher = outboxPublisher;
        this.tenantDirectory = tenantDirectory;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * The assigned Provider accepts. Repeating the call after a successful acceptance returns the
     * booking unchanged and publishes nothing, so an app retrying after a lost response is safe.
     *
     * @throws BookingException 404 when the caller is not the booking's assigned Provider; 409
     *         {@code PROVIDER_NOT_ASSIGNABLE} when they have left the booking's Tenant since the
     *         assignment; 503 when the Provider Service cannot confirm their membership
     * @throws InvalidTransitionException 409 when the booking is not PROVIDER_ASSIGNED
     */
    public Booking accept(String bookingKey, UUID providerId) {
        Booking snapshot = requireAssignedTo(bookingKey, providerId);
        if (snapshot.getStatus() == BookingStatus.PROVIDER_ASSIGNED && snapshot.getTenantId() != null) {
            requireStillMember(snapshot, providerId);
        }
        return transactions.execute(tx -> applyAcceptance(bookingKey, providerId));
    }

    private Booking applyAcceptance(String bookingKey, UUID providerId) {
        // Re-read inside the transaction; the booking's optimistic lock settles any race since.
        Booking booking = requireAssignedTo(bookingKey, providerId);
        if (booking.getStatus() == BookingStatus.PROVIDER_ACCEPTED) {
            log.debug("Booking {} already accepted by provider {}; treating as a retry",
                    booking.getId(), providerId);
            return booking;
        }
        transitionService.transition(booking, BookingStatus.PROVIDER_ACCEPTED,
                Actor.user(providerId, PROVIDER_ROLE), "Provider accepted the partner assignment");
        ProviderAcceptedEvent event = new ProviderAcceptedEvent(
                booking.getId(), booking.getCustomerId(), providerId,
                booking.getCreatedAt(), Instant.now(clock));
        outboxPublisher.publish(ProviderAcceptedEvent.AGGREGATE_TYPE, booking.getId(),
                ProviderAcceptedEvent.EVENT_TYPE, event);
        log.info("Booking {} accepted by assigned provider {} (tenant {})",
                booking.getId(), providerId, booking.getTenantId());
        return booking;
    }

    /**
     * The assigned Provider declines; the booking returns to its Tenant's queue.
     *
     * @throws BookingException 404 when the caller is not the booking's assigned Provider
     * @throws InvalidTransitionException 409 when the booking is not a PROVIDER_ASSIGNED booking that
     *         came through the Tenant fallback
     */
    @Transactional
    public Booking decline(String bookingKey, UUID providerId) {
        Booking booking = requireAssignedTo(bookingKey, providerId);
        if (booking.getQueuedForAssignmentAt() == null) {
            // An automatically dispatched job was never in a queue to go back to.
            throw new InvalidTransitionException(booking.getId(), booking.getStatus(),
                    BookingStatus.AWAITING_ASSIGNMENT);
        }
        BookingStatus from = booking.getStatus();
        if (from != BookingStatus.PROVIDER_ASSIGNED) {
            throw new InvalidTransitionException(booking.getId(), from, BookingStatus.AWAITING_ASSIGNMENT);
        }
        booking.setProviderId(null);
        transitionService.transition(booking, BookingStatus.AWAITING_ASSIGNMENT,
                Actor.user(providerId, PROVIDER_ROLE), "Provider declined the partner assignment");
        log.info("Booking {} declined by provider {}; back in the queue of tenant {}",
                booking.getId(), providerId, booking.getTenantId());
        return booking;
    }

    /** The Provider must still belong to the Tenant that assigned them (checked at acceptance time). */
    private void requireStillMember(Booking booking, UUID providerId) {
        Optional<TenantMembership> membership;
        try {
            membership = tenantDirectory.membership(booking.getTenantId(), providerId);
        } catch (TenantDirectoryUnavailableException e) {
            throw CallerTenantResolver.unavailable();
        }
        if (membership.isEmpty() || !membership.get().member()) {
            log.warn("Provider {} tried to accept booking {} but is no longer in tenant {}",
                    providerId, booking.getId(), booking.getTenantId());
            throw BookingException.providerNotAssignable(
                    "You are no longer a member of the partner agency that assigned this job");
        }
    }

    private Booking requireAssignedTo(String bookingKey, UUID providerId) {
        return bookingRepository.findByKey(bookingKey)
                .filter(b -> providerId != null && providerId.equals(b.getProviderId()))
                .orElseThrow(() -> BookingException.notFound(bookingKey));
    }
}
