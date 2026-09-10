package com.homefix.booking.domain;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository over {@code parts_line_item} rows recorded during job execution (Requirement
 * 6.8, 11.3).
 */
public interface PartsLineItemRepository extends JpaRepository<PartsLineItem, UUID> {

    List<PartsLineItem> findByBookingIdOrderByAddedAtAsc(UUID bookingId);
}
