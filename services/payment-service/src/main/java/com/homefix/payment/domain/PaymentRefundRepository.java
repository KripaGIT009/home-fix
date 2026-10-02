package com.homefix.payment.domain;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.repository.Repository;

/**
 * Persistence for {@link PaymentRefund} (Requirement 12.7). Deliberately a narrow
 * {@link Repository} exposing only what the refund flow needs. The unique {@code idempotency_key}
 * column is the authoritative guard against a retried request refunding twice.
 */
public interface PaymentRefundRepository extends Repository<PaymentRefund, UUID> {

    PaymentRefund save(PaymentRefund refund);

    Optional<PaymentRefund> findById(UUID id);

    Optional<PaymentRefund> findByIdempotencyKey(String idempotencyKey);

    /** @return whether the transaction has a refund in {@code status} (used to block concurrent refunds). */
    boolean existsByTransactionIdAndStatus(UUID transactionId, RefundStatus status);
}
