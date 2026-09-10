package com.homefix.location.domain;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository over the {@code location_history} table that holds the durable Provider location
 * trail for each Booking, retained for dispute resolution (Requirement 10.6).
 */
public interface LocationHistoryRepository extends JpaRepository<LocationHistory, UUID> {

    /**
     * Returns the full recorded location trail for a Booking in chronological order.
     */
    List<LocationHistory> findByBookingIdOrderByRecordedAtAsc(UUID bookingId);
}
