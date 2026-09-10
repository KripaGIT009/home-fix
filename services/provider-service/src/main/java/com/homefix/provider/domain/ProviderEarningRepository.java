package com.homefix.provider.domain;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence for {@link ProviderEarning} history entries (Requirement 14.5).
 */
public interface ProviderEarningRepository extends JpaRepository<ProviderEarning, UUID> {

    Page<ProviderEarning> findByProviderIdOrderByCreditedAtDesc(UUID providerId, Pageable pageable);
}
