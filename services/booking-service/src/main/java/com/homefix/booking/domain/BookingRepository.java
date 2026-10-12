package com.homefix.booking.domain;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repository over the {@code booking} aggregate.
 */
public interface BookingRepository extends JpaRepository<Booking, UUID> {

    Optional<Booking> findByReference(String reference);

    boolean existsByReference(String reference);

    /**
     * The booking a {@code /bookings/{key}} path names: its UUID — what the apps route on — or its
     * human-readable reference (e.g. {@code HFX-20261002-ABC123}). The two cannot collide: a
     * reference is never a well-formed UUID, so the form of the key decides the lookup.
     */
    default Optional<Booking> findByKey(String key) {
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        UUID id = canonicalUuid(key);
        return id != null ? findById(id) : findByReference(key);
    }

    /** {@code value} as a UUID only in its canonical 36-character form, else {@code null}. */
    private static UUID canonicalUuid(String value) {
        try {
            UUID id = UUID.fromString(value);
            // UUID.fromString accepts shortened groups ("1-2-3-4-5"); only the canonical form
            // counts as an id, so nothing else is mistaken for one.
            return id.toString().equalsIgnoreCase(value) ? id : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * A provider's jobs in the given states, soonest first — the query behind the provider
     * dashboard's active-job list (Requirement 28.8).
     */
    List<Booking> findByProviderIdAndStatusInOrderByScheduledAtAsc(
            UUID providerId, Collection<BookingStatus> statuses);

    /**
     * One page of a customer's bookings, newest first — the query behind the customer's service
     * history (Requirement 28.7). Served by {@code idx_booking_customer_created}
     * ({@code customer_id, created_at DESC}, migration V2).
     *
     * <p>The id is a tie-breaker, not a meaningful order: two bookings created in the same
     * instant would otherwise have no defined order, and an offset-paged query over an unstable
     * order can show a booking on two pages and drop another entirely.
     */
    Page<Booking> findByCustomerIdOrderByCreatedAtDescIdDesc(UUID customerId, Pageable pageable);

    /**
     * Bookings across all customers whose reference contains {@code fragment} (case-insensitive)
     * and whose status is one of {@code statuses}, newest first — the query behind the Admin
     * Portal's booking search (Requirement 19.2). An empty fragment matches every reference.
     *
     * <p>Spring Data escapes {@code %} and {@code _} in a {@code Containing} argument, so a search
     * term is matched literally rather than as a LIKE pattern. The result is a {@code List}, not a
     * {@code Page}: the portal shows one bounded list, so the count query a page would need is
     * skipped. The id breaks ties as in {@link #findByCustomerIdOrderByCreatedAtDescIdDesc}, so the
     * same search returns the same rows in the same order.
     */
    List<Booking> findByReferenceContainingIgnoreCaseAndStatusInOrderByCreatedAtDescIdDesc(
            String fragment, Collection<BookingStatus> statuses, Pageable pageable);

    /**
     * Any one of a customer's bookings in the given states that is sent to the given address — the
     * Customer Service asks before deleting a saved address (Requirement 2.5).
     */
    Optional<Booking> findFirstByCustomerIdAndAddressIdAndStatusIn(
            UUID customerId, UUID addressId, Collection<BookingStatus> statuses);

    /**
     * A Tenant's Assignment_Queue (Requirement MT-5.1), oldest queued first: bookings in
     * AWAITING_ASSIGNMENT for which {@code tenantId} was a Candidate_Tenant, plus those returned to
     * this Tenant by its Provider's decline. A declined booking carries its Tenant and belongs to
     * that Tenant's queue alone (Requirement MT-6.2), so candidacy counts only while the booking has
     * no Tenant yet. Served by {@code idx_booking_tenant_candidate_tenant} and
     * {@code idx_booking_assignment_deadline} (migration V3).
     */
    @Query("""
            select b from Booking b
            where b.status = com.homefix.booking.domain.BookingStatus.AWAITING_ASSIGNMENT
              and (b.tenantId = :tenantId
                   or (b.tenantId is null and exists (
                         select c from BookingTenantCandidate c
                         where c.bookingId = b.id and c.tenantId = :tenantId)))
            order by b.queuedForAssignmentAt asc, b.id asc
            """)
    List<Booking> findAssignmentQueue(@Param("tenantId") UUID tenantId, Pageable pageable);

    /**
     * A Tenant's bookings in the given states, newest first (Requirement MT-8.3), served by
     * {@code idx_booking_tenant_created}. The id breaks ties as in
     * {@link #findByCustomerIdOrderByCreatedAtDescIdDesc}.
     */
    List<Booking> findByTenantIdAndStatusInOrderByCreatedAtDescIdDesc(
            UUID tenantId, Collection<BookingStatus> statuses, Pageable pageable);

    /**
     * Bookings in one of {@code statuses} first queued before {@code cutoff}, oldest first — the
     * assignment-timeout sweeper's batch (Requirement MT-7.1, Property MT6): waiting in the queue, or
     * assigned to a Provider who has not confirmed. Served by the partial
     * {@code idx_booking_assignment_deadline} ({@code status, queued_for_assignment_at} where the
     * queue time is set, migration V3); the range condition implies the index predicate whatever the
     * bound values, so the lookup stays indexed under a generic prepared-statement plan.
     */
    List<Booking> findByStatusInAndQueuedForAssignmentAtBeforeOrderByQueuedForAssignmentAtAsc(
            Collection<BookingStatus> statuses, Instant cutoff, Pageable pageable);

    /**
     * Bookings still in SEARCHING_PROVIDER that entered it before {@code cutoff}, oldest first — the
     * stalled-search sweeper's batch (review 17.5 item 4). When a booking entered the state is read
     * from its audit trail ({@code idx_booking_audit_booking}); the status is a literal so the
     * partial {@code idx_booking_sweep_status} (migration V4) serves it under any plan.
     */
    @Query("""
            select b from Booking b
            where b.status = com.homefix.booking.domain.BookingStatus.SEARCHING_PROVIDER
              and (select max(a.transitionedAt) from BookingAudit a
                   where a.bookingId = b.id
                     and a.toState = com.homefix.booking.domain.BookingStatus.SEARCHING_PROVIDER) < :cutoff
            order by b.createdAt asc, b.id asc
            """)
    List<Booking> findSearchingProviderSince(@Param("cutoff") Instant cutoff, Pageable pageable);

    /**
     * Bookings still in CUSTOMER_APPROVAL_PENDING that entered it (most recently) before
     * {@code cutoff}, oldest first — the quote-approval timeout sweeper's batch (Requirement 9.9).
     * Read like {@link #findSearchingProviderSince}; a booking can enter the state more than once
     * (a second parts request after an approval), so the latest entry is the one that counts.
     */
    @Query("""
            select b from Booking b
            where b.status = com.homefix.booking.domain.BookingStatus.CUSTOMER_APPROVAL_PENDING
              and (select max(a.transitionedAt) from BookingAudit a
                   where a.bookingId = b.id
                     and a.toState = com.homefix.booking.domain.BookingStatus.CUSTOMER_APPROVAL_PENDING) < :cutoff
            order by b.createdAt asc, b.id asc
            """)
    List<Booking> findCustomerApprovalPendingSince(@Param("cutoff") Instant cutoff, Pageable pageable);
}
