package com.homefix.booking.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository over {@code job_interval} segments used for pause/resume tracking and net job
 * duration calculation (Requirement 11.5, 11.6, Property 10).
 */
public interface JobIntervalRepository extends JpaRepository<JobInterval, UUID> {

    /** All intervals for a booking, ordered chronologically by start. */
    List<JobInterval> findByBookingIdOrderByStartedAtAsc(UUID bookingId);

    /** The currently open interval for a booking (WORK while running, PAUSE while paused). */
    Optional<JobInterval> findByBookingIdAndEndedAtIsNull(UUID bookingId);
}
