package com.homefix.verification.domain;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence for {@link Verification} aggregates. The verification record is keyed by its own
 * id but looked up by {@code providerId} in the domain flows (Requirement 5).
 */
public interface VerificationRepository extends JpaRepository<Verification, UUID> {

    Optional<Verification> findByProviderId(UUID providerId);

    boolean existsByProviderId(UUID providerId);
}
