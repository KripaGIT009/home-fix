package com.homefix.payment.domain;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence for {@link PaymentTransaction}. The unique {@code idempotency_key} column plus the
 * {@link #findByIdempotencyKey(String)} lookup back the idempotency guarantee (Requirement 12.3,
 * Property 11).
 */
public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, UUID> {

    Optional<PaymentTransaction> findByIdempotencyKey(String idempotencyKey);

    Optional<PaymentTransaction> findByGatewayReference(String gatewayReference);
}
