package com.homefix.booking.domain;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository over the {@code booking} aggregate.
 */
public interface BookingRepository extends JpaRepository<Booking, UUID> {

    Optional<Booking> findByReference(String reference);

    boolean existsByReference(String reference);
}
