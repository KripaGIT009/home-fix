package com.homefix.rating.domain;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence for {@link ReviewPrompt}. Prompts are looked up by booking + reviewer role when a
 * review is submitted (Requirement 15.2) and by payment to short-circuit duplicate
 * {@code PaymentCompleted} deliveries (Requirement 15.1).
 */
public interface ReviewPromptRepository extends JpaRepository<ReviewPrompt, UUID> {

    Optional<ReviewPrompt> findByBookingIdAndReviewerRole(UUID bookingId, ReviewerRole reviewerRole);

    boolean existsByPaymentId(UUID paymentId);
}
