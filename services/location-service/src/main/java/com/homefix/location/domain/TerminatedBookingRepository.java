package com.homefix.location.domain;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository over the {@code terminated_booking} table recording Bookings whose location feed
 * has been shut down on JOB_STARTED (Requirement 10.5).
 */
public interface TerminatedBookingRepository extends JpaRepository<TerminatedBooking, UUID> {

    boolean existsByBookingId(UUID bookingId);
}
