package com.homefix.booking.domain;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository over the immutable {@code booking_audit} trail.
 */
public interface BookingAuditRepository extends JpaRepository<BookingAudit, UUID> {

    /** Returns the audit chain for a booking in transition order (Property 9). */
    List<BookingAudit> findByBookingIdOrderByTransitionedAtAsc(UUID bookingId);
}
