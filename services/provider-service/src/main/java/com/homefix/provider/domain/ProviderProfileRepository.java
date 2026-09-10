package com.homefix.provider.domain;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence for {@link ProviderProfile} aggregates.
 */
public interface ProviderProfileRepository extends JpaRepository<ProviderProfile, UUID> {

    Optional<ProviderProfile> findByUserId(UUID userId);
}
