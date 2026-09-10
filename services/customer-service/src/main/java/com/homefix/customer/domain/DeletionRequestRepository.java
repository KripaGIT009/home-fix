package com.homefix.customer.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DeletionRequestRepository extends JpaRepository<DeletionRequest, UUID> {

    /** Acknowledged requests whose anonymization deadline has arrived (Requirement 26.9). */
    List<DeletionRequest> findByStatusAndAnonymizeAfterLessThanEqual(
            DeletionRequest.Status status, Instant deadline);

    List<DeletionRequest> findByCustomerId(UUID customerId);
}
