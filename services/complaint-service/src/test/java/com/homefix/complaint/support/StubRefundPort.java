package com.homefix.complaint.support;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.homefix.complaint.payment.RefundPort;
import com.homefix.complaint.payment.RefundResult;

/**
 * Configurable {@link RefundPort} test double. Returns a preset {@link RefundResult} (approved or
 * rejected), or throws a preset exception, so the happy path (16.5), the REFUND_FAILED flow (16.6)
 * and the unknown-outcome path can be exercised. Records each request's amount and idempotency key,
 * and whether a transaction was active when it was called.
 */
public class StubRefundPort implements RefundPort {

    private RefundResult nextResult;
    private RuntimeException nextFailure;
    private final List<BigDecimal> requests = new ArrayList<>();
    private final List<String> idempotencyKeys = new ArrayList<>();
    private final List<Boolean> calledInTransaction = new ArrayList<>();

    public StubRefundPort(RefundResult nextResult) {
        this.nextResult = nextResult;
    }

    public static StubRefundPort approving() {
        return new StubRefundPort(RefundResult.approved("stub_rf_ref"));
    }

    public static StubRefundPort rejecting(String reason) {
        return new StubRefundPort(RefundResult.rejected(reason));
    }

    public void setNextResult(RefundResult nextResult) {
        this.nextResult = nextResult;
    }

    /** Makes subsequent calls throw {@code failure} instead of returning a result. */
    public void failWith(RuntimeException failure) {
        this.nextFailure = failure;
    }

    @Override
    public RefundResult requestRefund(UUID bookingId, UUID complaintId, BigDecimal amount,
                                      String idempotencyKey) {
        requests.add(amount);
        idempotencyKeys.add(idempotencyKey);
        calledInTransaction.add(TransactionSynchronizationManager.isActualTransactionActive());
        if (nextFailure != null) {
            throw nextFailure;
        }
        return nextResult;
    }

    public List<BigDecimal> requests() {
        return requests;
    }

    public List<String> idempotencyKeys() {
        return idempotencyKeys;
    }

    public List<Boolean> calledInTransaction() {
        return calledInTransaction;
    }
}
