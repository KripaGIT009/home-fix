package com.homefix.customer.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AddressRepository extends JpaRepository<Address, UUID> {

    List<Address> findByCustomerIdAndIsActiveTrue(UUID customerId);

    long countByCustomerIdAndIsActiveTrue(UUID customerId);

    Optional<Address> findByIdAndCustomerId(UUID id, UUID customerId);

    /** Remaining active addresses (default-promotion candidates), most recent first. */
    List<Address> findByCustomerIdAndIsActiveTrueOrderByCreatedAtDesc(UUID customerId);
}
