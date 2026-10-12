package com.homefix.booking.service;

import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

import com.homefix.booking.address.CustomerAddressPort;
import com.homefix.booking.address.CustomerAddressPort.ServiceAddress;
import com.homefix.booking.domain.Booking;
import com.homefix.booking.domain.BookingRepository;
import com.homefix.booking.domain.BookingStatus;
import com.homefix.booking.domain.BookingTenantCandidateRepository;
import com.homefix.booking.service.BookingQueryService.BookingView;
import com.homefix.booking.tenant.TenantDirectoryPort;
import com.homefix.booking.tenant.TenantDirectoryPort.TenantMembership;
import com.homefix.booking.tenant.TenantDirectoryPort.TenantSummary;
import com.homefix.booking.tenant.TenantDirectoryUnavailableException;

/**
 * The Tenant Portal's side of booking-service (Requirements MT-5, MT-8.3): a Tenant_Admin's
 * Assignment_Queue, their Tenant's bookings, and the assignment of a queued booking to one of the
 * Tenant's Providers.
 *
 * <h2>Isolation (Requirement MT-10, Property MT5)</h2>
 * The caller's Tenant always comes from their identity ({@link CallerTenantResolver}); nothing in a
 * request names a Tenant. A booking is <em>visible</em> to a Tenant when the Tenant serves it
 * ({@code tenant_id}) or was one of its Candidate_Tenants; anything else answers the same 404 as a
 * missing booking (Requirement MT-5.6). A visible booking may be <em>assigned</em> by the Tenant
 * only while it is AWAITING_ASSIGNMENT and either has no Tenant yet (the Tenant is a candidate) or
 * was returned to this Tenant by its Provider's decline (Requirement MT-5.2); otherwise 409
 * {@code BOOKING_NOT_ASSIGNABLE}, which is also what the loser of a race sees.
 *
 * <h2>Assignment and the race (Requirement MT-5.4, Property MT3)</h2>
 * The booking is read and checked, the Provider's assignability is asked of the Provider Service
 * (outside any transaction: it is an HTTP call), and then the read copy is merged back in a short
 * transaction. The merge carries the version that was read, so if another Tenant_Admin's assignment
 * committed in between, the booking's optimistic lock rejects this one and the caller gets 409; at
 * most one assignment can ever commit. Tenant, Provider, the transition to PROVIDER_ASSIGNED, its
 * audit row and the {@code ProviderAssigned} outbox row (now naming the Tenant) share that
 * transaction (Requirement MT-5.3).
 */
@Service
public class TenantBookingService {

    private static final Logger log = LoggerFactory.getLogger(TenantBookingService.class);

    /** Most rows either list returns (Requirement MT-8.3); the portal shows one bounded list. */
    public static final int LIST_LIMIT = 200;

    /** Audit role for a Tenant_Admin's transitions. */
    static final String TENANT_ADMIN_ROLE = "TENANT_ADMIN";

    private final BookingRepository bookingRepository;
    private final BookingTenantCandidateRepository candidateRepository;
    private final CallerTenantResolver tenantResolver;
    private final TenantDirectoryPort tenantDirectory;
    private final BookingTransitionService transitionService;
    private final BookingQueryService queryService;
    private final CustomerAddressPort addresses;
    private final TransactionOperations transactions;

    public TenantBookingService(BookingRepository bookingRepository,
                                BookingTenantCandidateRepository candidateRepository,
                                CallerTenantResolver tenantResolver,
                                TenantDirectoryPort tenantDirectory,
                                BookingTransitionService transitionService,
                                BookingQueryService queryService,
                                CustomerAddressPort addresses,
                                TransactionOperations transactions) {
        this.bookingRepository = bookingRepository;
        this.candidateRepository = candidateRepository;
        this.tenantResolver = tenantResolver;
        this.tenantDirectory = tenantDirectory;
        this.transitionService = transitionService;
        this.queryService = queryService;
        this.addresses = addresses;
        this.transactions = transactions;
    }

    /**
     * The caller's Tenant's Assignment_Queue, oldest queued first (Requirement MT-5.1), with each
     * booking's service address and coordinates so the admin can pick a nearby Provider.
     */
    public List<TenantBookingView> queue(UUID callerId) {
        TenantSummary tenant = tenantResolver.requireActiveTenant(callerId);
        return withAddresses(bookingRepository.findAssignmentQueue(
                tenant.tenantId(), PageRequest.of(0, LIST_LIMIT)));
    }

    /**
     * The caller's Tenant's bookings, newest first, at most {@value #LIST_LIMIT}, optionally in one
     * status (Requirement MT-8.3).
     *
     * @param status optional; null lists every status
     */
    public List<TenantBookingView> bookings(UUID callerId, BookingStatus status) {
        TenantSummary tenant = tenantResolver.requireActiveTenant(callerId);
        Collection<BookingStatus> statuses = status == null
                ? EnumSet.allOf(BookingStatus.class)
                : EnumSet.of(status);
        return withAddresses(bookingRepository.findByTenantIdAndStatusInOrderByCreatedAtDescIdDesc(
                tenant.tenantId(), statuses, PageRequest.of(0, LIST_LIMIT)));
    }

    /**
     * Assigns the queued booking {@code bookingKey} (UUID or reference) to {@code providerId}, one of
     * the caller's Tenant's Assignable_Providers (Requirement MT-5.2, MT-5.3).
     *
     * @throws BookingException 404 when the booking is not visible to the caller's Tenant; 409
     *         {@code BOOKING_NOT_ASSIGNABLE} when it is not waiting for this Tenant or another
     *         assignment won; 409 {@code PROVIDER_NOT_ASSIGNABLE} when the Provider is not an
     *         Assignable_Provider of the Tenant; 403 {@code TENANT_SUSPENDED}; 503 when the Provider
     *         Service cannot be asked
     */
    public TenantBookingView assign(UUID callerId, String bookingKey, UUID providerId) {
        if (providerId == null) {
            throw BookingException.validation("providerId is required");
        }
        // A change: the caller's admin role is confirmed now, never from a cached answer.
        TenantSummary tenant = tenantResolver.requireActiveTenantNow(callerId);
        UUID tenantId = tenant.tenantId();

        Booking snapshot = bookingRepository.findByKey(bookingKey)
                .filter(b -> visibleTo(b, tenantId))
                .orElseThrow(() -> BookingException.notFound(bookingKey));
        if (!assignableBy(snapshot, tenantId)) {
            throw BookingException.notAssignable(
                    "Booking " + snapshot.getReference() + " is no longer waiting for your assignment");
        }
        requireAssignable(tenantId, providerId);

        Booking assigned;
        try {
            assigned = transactions.execute(tx -> {
                // Merging the copy read above carries its version: if anything changed the booking
                // since — typically another Tenant's assignment — the optimistic lock refuses it.
                Booking booking = bookingRepository.save(snapshot);
                booking.setTenantId(tenantId);
                booking.setProviderId(providerId);
                transitionService.transitionForTenant(booking, BookingStatus.PROVIDER_ASSIGNED,
                        Actor.user(callerId, TENANT_ADMIN_ROLE),
                        "Partner " + tenantId + " assigned provider " + providerId, tenant.name());
                return bookingRepository.saveAndFlush(booking);
            });
        } catch (OptimisticLockingFailureException e) {
            log.info("Tenant {} lost the assignment race for booking {}", tenantId, snapshot.getId());
            throw BookingException.notAssignable(
                    "Booking " + snapshot.getReference() + " was just assigned by someone else");
        }
        log.info("Tenant {} assigned booking {} to provider {}", tenantId, snapshot.getId(), providerId);
        return withAddress(queryService.labelled(assigned));
    }

    /** A booking the Tenant serves or was a candidate for (Property MT5). */
    private boolean visibleTo(Booking booking, UUID tenantId) {
        return tenantId.equals(booking.getTenantId())
                || candidateRepository.existsByBookingIdAndTenantId(booking.getId(), tenantId);
    }

    /**
     * Waiting, and waiting for this Tenant: either nobody has it yet (and the Tenant is a candidate,
     * checked by {@link #visibleTo}) or its Provider declined it back to this Tenant.
     */
    private static boolean assignableBy(Booking booking, UUID tenantId) {
        return booking.getStatus() == BookingStatus.AWAITING_ASSIGNMENT
                && (booking.getTenantId() == null || tenantId.equals(booking.getTenantId()));
    }

    /** Property MT4: only the Tenant's own members, APPROVED and not under review. */
    private void requireAssignable(UUID tenantId, UUID providerId) {
        Optional<TenantMembership> membership;
        try {
            membership = tenantDirectory.membership(tenantId, providerId);
        } catch (TenantDirectoryUnavailableException e) {
            throw CallerTenantResolver.unavailable();
        }
        if (membership.isEmpty() || !membership.get().member() || !membership.get().assignable()) {
            throw BookingException.providerNotAssignable(
                    "That provider cannot be assigned: they must be an approved member of your team");
        }
    }

    private List<TenantBookingView> withAddresses(List<Booking> bookings) {
        return queryService.labelled(bookings).stream().map(this::withAddress).toList();
    }

    private TenantBookingView withAddress(BookingView view) {
        return new TenantBookingView(view, addresses.find(view.booking().getAddressId()).orElse(null));
    }

    /**
     * A booking as the Tenant Portal shows it: the labelled booking plus its service address, null
     * when the Customer Service cannot resolve it.
     */
    public record TenantBookingView(BookingView view, ServiceAddress address) {
    }
}
