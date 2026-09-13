package com.homefix.provider.domain;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence for {@link Settlement} requests.
 */
public interface SettlementRepository extends JpaRepository<Settlement, UUID> {

    /** Settlement request history, newest first (Requirement 14.3). */
    List<Settlement> findByProviderIdOrderByRequestedAtDesc(UUID providerId);
}
