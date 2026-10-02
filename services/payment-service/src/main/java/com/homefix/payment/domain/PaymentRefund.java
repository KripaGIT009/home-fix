package com.homefix.payment.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.homefix.payment.service.PaymentException;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * One refund request against a {@link PaymentTransaction} (Requirement 12.7).
 *
 * <p>The record is the refund's idempotency anchor: {@code idempotency_key} is unique and is derived
 * from the transaction id plus the client-supplied idempotency key, so a retried refund request
 * finds the original record instead of refunding twice. It is created in {@link RefundStatus#PENDING}
 * and committed before the gateway is called; the gateway outcome then moves it to
 * {@link RefundStatus#SUCCEEDED} or, on an explicit rejection, {@link RefundStatus#FAILED}. When the
 * gateway call yields no answer it stays PENDING, and its {@link #getId() id} &mdash; the gateway-side
 * idempotency key &mdash; lets it be re-sent without refunding twice.
 */
@Entity
@Table(name = "payment_refund",
        indexes = @Index(name = "ix_payment_refund_transaction", columnList = "transaction_id"))
public class PaymentRefund {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "transaction_id", nullable = false, updatable = false)
    private UUID transactionId;

    /** {@code refund:<transactionId>:<client key>}; unique, so a retry cannot create a second refund. */
    @Column(name = "idempotency_key", nullable = false, unique = true, updatable = false, length = 128)
    private String idempotencyKey;

    @Column(name = "amount", nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private RefundStatus status;

    @Column(name = "gateway_reference", length = 128)
    private String gatewayReference;

    @Column(name = "failure_reason", length = PaymentTransaction.FAILURE_REASON_MAX_LENGTH)
    private String failureReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version")
    private long version;

    protected PaymentRefund() {
        // JPA
    }

    private PaymentRefund(UUID transactionId, String idempotencyKey, BigDecimal amount) {
        this.id = UUID.randomUUID();
        this.transactionId = transactionId;
        this.idempotencyKey = idempotencyKey;
        this.amount = amount;
        this.status = RefundStatus.PENDING;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    /** Creates a PENDING refund reservation; persist it before calling the gateway. */
    public static PaymentRefund reserve(UUID transactionId, String idempotencyKey, BigDecimal amount) {
        return new PaymentRefund(transactionId, idempotencyKey, amount);
    }

    /** Records that the gateway executed the refund. */
    public void markSucceeded(String gatewayRefundReference) {
        requirePending(RefundStatus.SUCCEEDED);
        this.status = RefundStatus.SUCCEEDED;
        this.gatewayReference = gatewayRefundReference;
        this.updatedAt = Instant.now();
    }

    /** Records that the gateway explicitly rejected the refund; no money moved. */
    public void markFailed(String reason) {
        requirePending(RefundStatus.FAILED);
        this.status = RefundStatus.FAILED;
        this.failureReason = truncate(reason);
        this.updatedAt = Instant.now();
    }

    /**
     * Records why the last gateway call for this refund produced no answer (timeout, connection
     * reset) while leaving it PENDING: the gateway may or may not have executed it, so it must be
     * re-sent under the same refund id rather than written off as FAILED.
     */
    public void recordUnknownOutcome(String reason) {
        requirePending(RefundStatus.PENDING);
        this.failureReason = truncate(reason);
        this.updatedAt = Instant.now();
    }

    private static String truncate(String reason) {
        return reason != null && reason.length() > PaymentTransaction.FAILURE_REASON_MAX_LENGTH
                ? reason.substring(0, PaymentTransaction.FAILURE_REASON_MAX_LENGTH)
                : reason;
    }

    private void requirePending(RefundStatus target) {
        if (status != RefundStatus.PENDING) {
            throw PaymentException.invalidTransition(
                    "Refund " + id + " cannot move from " + status + " to " + target);
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getTransactionId() {
        return transactionId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public RefundStatus getStatus() {
        return status;
    }

    public String getGatewayReference() {
        return gatewayReference;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
