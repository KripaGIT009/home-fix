package com.homefix.customer.domain;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerProfileRepository extends JpaRepository<CustomerProfile, UUID> {

    Optional<CustomerProfile> findByUserId(UUID userId);
}
