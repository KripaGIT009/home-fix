package com.homefix.provider.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence for {@link ProviderEarning} history entries (Requirement 14.5).
 */
public interface ProviderEarningRepository extends JpaRepository<ProviderEarning, UUID> {

    Page<ProviderEarning> findByProviderIdOrderByCreditedAtDesc(UUID providerId, Pageable pageable);

    /**
     * Ledger entries credited at or after {@code from}, used to build the dashboard's
     * "today" snapshot (Requirement 14.1). The window is half-open on the lower bound only,
     * so the caller decides where the day starts in the provider's own zone.
     */
    List<ProviderEarning> findByProviderIdAndCreditedAtGreaterThanEqual(UUID providerId, Instant from);

    /** Whether the booking's job credit has already been applied (it may be delivered twice). */
    boolean existsByBookingIdAndType(UUID bookingId, EarningType type);
}
