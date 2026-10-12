package com.homefix.provider.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
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

    /**
     * The booking's job credit line, if one was applied (it may be delivered twice). A booking pays one provider, so there is at
     * most one (unique index, migration V2); returning it lets the caller tell a repeat of the same
     * credit apart from a credit of the same booking to a different provider.
     */
    Optional<ProviderEarning> findFirstByBookingIdAndType(UUID bookingId, EarningType type);
}
