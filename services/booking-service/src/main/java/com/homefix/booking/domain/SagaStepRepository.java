package com.homefix.booking.domain;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository over the Saga log ({@code saga_step}).
 */
public interface SagaStepRepository extends JpaRepository<SagaStep, UUID> {

    List<SagaStep> findByBookingIdOrderBySequenceNoAsc(UUID bookingId);
}
