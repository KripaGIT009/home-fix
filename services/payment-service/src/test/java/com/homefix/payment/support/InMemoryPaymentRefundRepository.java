package com.homefix.payment.support;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.homefix.payment.domain.PaymentRefund;
import com.homefix.payment.domain.PaymentRefundRepository;
import com.homefix.payment.domain.RefundStatus;

/**
 * In-memory {@link PaymentRefundRepository} for service unit tests. Enforces the unique
 * {@code idempotency_key} constraint the real table has, so a test that would create a second refund
 * for the same key fails loudly instead of passing silently.
 */
public class InMemoryPaymentRefundRepository implements PaymentRefundRepository {

    private final Map<UUID, PaymentRefund> store = new LinkedHashMap<>();

    @Override
    public PaymentRefund save(PaymentRefund refund) {
        store.values().stream()
                .filter(r -> !r.getId().equals(refund.getId())
                        && r.getIdempotencyKey().equals(refund.getIdempotencyKey()))
                .findAny()
                .ifPresent(r -> {
                    throw new org.springframework.dao.DataIntegrityViolationException(
                            "duplicate refund idempotency_key " + refund.getIdempotencyKey());
                });
        store.put(refund.getId(), refund);
        return refund;
    }

    @Override
    public Optional<PaymentRefund> findById(UUID id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public Optional<PaymentRefund> findByIdempotencyKey(String idempotencyKey) {
        return store.values().stream()
                .filter(r -> r.getIdempotencyKey().equals(idempotencyKey))
                .findFirst();
    }

    @Override
    public boolean existsByTransactionIdAndStatus(UUID transactionId, RefundStatus status) {
        return store.values().stream()
                .anyMatch(r -> r.getTransactionId().equals(transactionId) && r.getStatus() == status);
    }

    public List<PaymentRefund> findAll() {
        return new ArrayList<>(store.values());
    }
}
