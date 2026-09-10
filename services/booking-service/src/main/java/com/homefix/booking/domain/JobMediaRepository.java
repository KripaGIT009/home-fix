package com.homefix.booking.domain;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository over {@code job_media} references.
 */
public interface JobMediaRepository extends JpaRepository<JobMedia, UUID> {

    List<JobMedia> findByBookingId(UUID bookingId);

    long countByBookingId(UUID bookingId);

    /** Count of media of a given logical type (e.g. BEFORE_PHOTO, AFTER_PHOTO) for a booking. */
    long countByBookingIdAndType(UUID bookingId, String type);
}
