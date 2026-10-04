package com.homefix.booking.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository over the immutable {@code booking_audit} trail.
 */
public interface BookingAuditRepository extends JpaRepository<BookingAudit, UUID> {

    /** Returns the audit chain for a booking in transition order (Property 9). */
    List<BookingAudit> findByBookingIdOrderByTransitionedAtAsc(UUID bookingId);

    /**
     * When the booking last entered {@code status}, from its audit chain; empty if it never did.
     * The sweepers use it to re-check a booking's deadline inside their own transaction.
     */
    default Optional<Instant> lastEnteredAt(UUID bookingId, BookingStatus status) {
        return findByBookingIdOrderByTransitionedAtAsc(bookingId).stream()
                .filter(a -> a.getToState() == status)
                .map(BookingAudit::getTransitionedAt)
                .reduce((earlier, later) -> later);
    }
}
