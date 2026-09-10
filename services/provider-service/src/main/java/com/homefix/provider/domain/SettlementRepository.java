package com.homefix.provider.domain;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence for {@link Settlement} requests.
 */
public interface SettlementRepository extends JpaRepository<Settlement, UUID> {
}
