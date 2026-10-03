package com.homefix.booking.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

import com.homefix.booking.address.CustomerAddressPort;
import com.homefix.booking.address.CustomerAddressPort.ServiceAddress;
import com.homefix.booking.domain.Booking;
import com.homefix.booking.domain.BookingRepository;
import com.homefix.booking.domain.BookingStatus;
import com.homefix.booking.domain.BookingTenantCandidate;
import com.homefix.booking.domain.BookingTenantCandidateRepository;
import com.homefix.booking.tenant.TenantDirectoryPort;
import com.homefix.booking.tenant.TenantDirectoryPort.CoveringTenant;
import com.homefix.booking.tenant.TenantDirectoryPort.TenantRef;


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
 * intermediate PROVIDER_ASSIGNED state by a partial failure. For the same reason the intermediate
 * step is applied with {@link BookingTransitionService#transitionPassingThrough}: it is audited, but
 * no ProviderAssigned event is published for a state nobody can observe. The acceptance itself is
 * announced by the Dispatch Engine's {@code ProviderAccepted}.
 *
 * <p>Assigning {@code providerId} here is the only place the Dispatch Engine's choice is recorded.
 * Without it every downstream provider event carried a null provider.
 *
 * <h2>Tenant fallback (Requirement MT-4)</h2>
 * When dispatch reports that nobody accepted, the booking is offered to the Tenants covering its
 * address and category instead of failing outright (design D3): their ids are snapshotted as
 * Candidate_Tenants and the booking enters AWAITING_ASSIGNMENT, with no {@code BookingCancelled}.
 * Only when no Tenant covers it, or either lookup fails, does it fail as before (Requirement MT-4.3,
 * MT-4.4), so a dependency outage never leaves a booking in SEARCHING_PROVIDER.
 *
 * <h2>Why the HTTP lookups run outside the transaction</h2>
 * The address, coverage and Tenant-of-provider lookups are network calls with retries. Holding a
 * pooled connection and the booking row across them is the pattern the review flagged on the create
 * path, so each entry point reads the booking, asks its questions, and only then opens a short
 * transaction that re-reads the booking and applies the outcome. The re-read keeps both callbacks
 * idempotent under a concurrent redelivery, and the booking's optimistic lock settles any race the
 * re-read cannot see.
 */
@Service
public class DispatchOutcomeService {

    private static final Logger log = LoggerFactory.getLogger(DispatchOutcomeService.class);

    /** Audit actor for transitions the Dispatch Engine requests. */
    private static final String DISPATCH_ACTOR_ROLE = "dispatch-engine";

    private final BookingRepository bookingRepository;
    private final BookingTransitionService transitionService;
    private final BookingTenantCandidateRepository candidateRepository;
    private final CustomerAddressPort addresses;
    private final TenantDirectoryPort tenantDirectory;
    private final TransactionOperations transactions;
    private final Clock clock;

    public DispatchOutcomeService(BookingRepository bookingRepository,
                                  BookingTransitionService transitionService,
                                  BookingTenantCandidateRepository candidateRepository,
                                  CustomerAddressPort addresses,
                                  TenantDirectoryPort tenantDirectory,
                                  TransactionOperations transactions,
                                  Clock clock) {
        this.bookingRepository = bookingRepository;
        this.transitionService = transitionService;
        this.candidateRepository = candidateRepository;
        this.addresses = addresses;
        this.tenantDirectory = tenantDirectory;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * Records that a provider accepted the job: assigns the provider, records the provider's Tenant
     * if they belong to one (Requirement MT-8.1), and walks the booking to PROVIDER_ACCEPTED.
     *
     * <p>Idempotent for the same provider. A redelivered dispatch callback for a booking already
     * accepted by that provider is a no-op rather than a 409, because the Dispatch Engine retries
     * this call through its resilience stack and must be able to do so safely. A different provider
     * claiming an already-accepted booking is still rejected.
     *
     * <p>The Tenant lookup is best effort: when the Provider Service cannot answer, the acceptance
     * still succeeds and the booking carries no Tenant (Requirement MT-8.2). It only labels the
     * booking for the Tenant's oversight; failing an acceptance over it would lose the customer a
     * professional who already said yes.
     *
     * @throws BookingException 404 when the booking does not exist, 409 when the transition is not
     *                          legal from the current state or another provider already holds it
     */
    public Booking markProviderAccepted(UUID bookingId, UUID providerId) {
        if (providerId == null) {
            throw BookingException.validation("providerId is required");
        }
        Booking current = requireBooking(bookingId);
        UUID tenantId = current.getStatus() == BookingStatus.PROVIDER_ACCEPTED ? null : tenantOf(providerId);
        return transactions.execute(tx -> applyProviderAccepted(bookingId, providerId, tenantId));
    }

    private Booking applyProviderAccepted(UUID bookingId, UUID providerId, UUID tenantId) {
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
        booking.setTenantId(tenantId);
        transitionService.transitionPassingThrough(booking, BookingStatus.PROVIDER_ASSIGNED,
                dispatchActor(providerId), "Dispatch Engine assigned provider " + providerId);
        Booking accepted = transitionService.transition(booking, BookingStatus.PROVIDER_ACCEPTED,
                dispatchActor(providerId), "Provider accepted the job offer");
        log.info("Booking {} assigned to and accepted by provider {} tenant={}", bookingId, providerId, tenantId);
        return accepted;
    }

    /**
     * Records that dispatch exhausted every candidate and radius cycle without an acceptance, and
     * routes the booking to the Tenants covering it, or fails it when there are none (Requirement
     * MT-4, Property MT1). The returned booking's status tells the Dispatch Engine which happened:
     * it sends its "no provider" notices only for SEARCHING_FAILED.
     *
     * <p>Idempotent: a booking already in SEARCHING_FAILED, or already routed to Tenants (it has a
     * queue time, whatever has happened since), is returned unchanged.
     *
     * @throws BookingException 404 when the booking does not exist, 409 when neither outcome is legal
     *                          from the current state
     */
    public Booking markSearchingFailed(UUID bookingId) {
        Booking current = requireBooking(bookingId);
        if (alreadySettled(current)) {
            log.debug("Booking {} already settled as {}; treating as a retry", bookingId, current.getStatus());
            return current;
        }
        // Only a booking still searching can fall back; anything else is refused by the transition
        // below exactly as before, without asking other services first.
        List<CoveringTenant> tenants = current.getStatus() == BookingStatus.SEARCHING_PROVIDER
                ? coveringTenants(current)
                : List.of();
        return transactions.execute(tx -> applySearchOutcome(bookingId, tenants));
    }

    private Booking applySearchOutcome(UUID bookingId, List<CoveringTenant> tenants) {
        Booking booking = requireBooking(bookingId);
        if (alreadySettled(booking)) {
            return booking;
        }
        if (tenants.isEmpty()) {
            Booking failed = transitionService.transition(booking, BookingStatus.SEARCHING_FAILED,
                    Actor.system(), "Dispatch Engine found no available provider");
            log.warn("Booking {} marked SEARCHING_FAILED: no provider accepted", bookingId);
            return failed;
        }
        // Transition first: an illegal source state throws before any candidate row is written.
        booking.setQueuedForAssignmentAt(Instant.now(clock));
        transitionService.transition(booking, BookingStatus.AWAITING_ASSIGNMENT, Actor.system(),
                "No provider accepted; routed to " + tenants.size() + " partner(s)");
        tenants.stream()
                .map(CoveringTenant::tenantId)
                .distinct()
                .forEach(tenantId -> candidateRepository.save(new BookingTenantCandidate(bookingId, tenantId)));
        log.info("Booking {} routed to {} partner(s) for assignment", bookingId, tenants.size());
        return booking;
    }

    /** Failed already, or already routed to Tenants by an earlier delivery of the same callback. */
    private static boolean alreadySettled(Booking booking) {
        return booking.getStatus() == BookingStatus.SEARCHING_FAILED
                || booking.getQueuedForAssignmentAt() != null;
    }

    /**
     * The Tenants covering the booking's address and category; empty when none do <em>or</em> when
     * the address or the coverage cannot be looked up, which is logged so the outage is visible
     * (Requirement MT-4.4).
     */
    private List<CoveringTenant> coveringTenants(Booking booking) {
        Optional<ServiceAddress> address;
        try {
            address = addresses.find(booking.getAddressId());
        } catch (RuntimeException e) {
            log.warn("Booking {}: service address lookup failed; failing without Tenant fallback: {}",
                    booking.getId(), e.getMessage());
            return List.of();
        }
        if (address.isEmpty()) {
            log.warn("Booking {}: service address {} could not be resolved; failing without Tenant fallback",
                    booking.getId(), booking.getAddressId());
            return List.of();
        }
        try {
            return tenantDirectory.covering(address.get().latitude(), address.get().longitude(),
                    booking.getCategoryId());
        } catch (RuntimeException e) {
            log.warn("Booking {}: Tenant coverage lookup failed; failing without Tenant fallback: {}",
                    booking.getId(), e.getMessage());
            return List.of();
        }
    }

    /** The Provider's Tenant id, or null when independent or when it cannot be determined. */
    private UUID tenantOf(UUID providerId) {
        try {
            return tenantDirectory.ofProvider(providerId).map(TenantRef::tenantId).orElse(null);
        } catch (RuntimeException e) {
            log.warn("Tenant of provider {} could not be determined; booking carries no Tenant: {}",
                    providerId, e.getMessage());
            return null;
        }
    }

    private Actor dispatchActor(UUID providerId) {
        return Actor.user(providerId, DISPATCH_ACTOR_ROLE);
    }

    private Booking requireBooking(UUID bookingId) {
        return bookingRepository.findById(bookingId)
                .orElseThrow(() -> BookingException.notFound(String.valueOf(bookingId)));
    }
}
