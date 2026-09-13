package com.homefix.booking.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository over the {@code booking} aggregate.
 */
public interface BookingRepository extends JpaRepository<Booking, UUID> {

    Optional<Booking> findByReference(String reference);

    boolean existsByReference(String reference);

    /**
     * A provider's jobs in the given states, soonest first — the query behind the provider
     * dashboard's active-job list (Requirement 28.8).
     */
    List<Booking> findByProviderIdAndStatusInOrderByScheduledAtAsc(
            UUID providerId, Collection<BookingStatus> statuses);
}
