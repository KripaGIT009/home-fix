package com.homefix.booking.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
