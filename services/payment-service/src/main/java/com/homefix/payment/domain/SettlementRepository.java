package com.homefix.payment.domain;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence for {@link Settlement} bank transfers (Requirement 14.3-14.4).
 */
public interface SettlementRepository extends JpaRepository<Settlement, UUID> {
}
