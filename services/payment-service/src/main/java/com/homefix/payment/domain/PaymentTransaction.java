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
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * A payment transaction aggregate (Requirement 12).
 *
 * <p>Created in {@link TransactionStatus#PENDING}. State changes go exclusively through
 * {@link #transitionTo(TransactionStatus)}, which enforces the transaction state machine
 * (Requirement 12.4, Property 12) so no caller can put the aggregate into an illegal state.
 *
 * <p>Monetary values use {@link BigDecimal} throughout. Raw card numbers are never stored; any
 * gateway payment credential is persisted only as KMS/AES ciphertext (Requirement 12.9).
 */
@Entity
@Table(name = "payment_transaction")
public class PaymentTransaction {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** Deterministic idempotency key scoped to (customerId, bookingId) (Requirement 12.3). */
    @Column(name = "idempotency_key", nullable = false, unique = true, updatable = false, length = 128)
    private String idempotencyKey;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @Column(name = "booking_id", nullable = false, updatable = false)
    private UUID bookingId;

    @Column(name = "provider_id", nullable = false, updatable = false)
    private UUID providerId;

    @Column(name = "amount", nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal amount;

    /** Platform fee amount deducted from {@link #amount} to compute provider net (Requirement 12.10). */
    @Column(name = "platform_fee", nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal platformFee;

    @Enumerated(EnumType.STRING)
    @Column(name = "method", nullable = false, length = 24, updatable = false)
    private PaymentMethod method;

    @Column(name = "gateway", nullable = false, length = 32, updatable = false)
    private String gateway;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private TransactionStatus status;

    /** Reference returned by the gateway when the charge is initiated. */
    @Column(name = "gateway_reference", length = 128)
    private String gatewayReference;

    /**
     * KMS/AES ciphertext of any stored payment credential token (never a raw card number)
     * (Requirement 12.9). May be {@code null} for methods that store nothing (e.g. CASH).
     */
    @Column(name = "payment_credential_encrypted")
    private String paymentCredentialEncrypted;

    /** Amount already refunded (0 for a non-refunded transaction). */
    @Column(name = "refunded_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal refundedAmount;

    /** Number of customer-driven retry attempts made so far (Requirement 12.8). */
    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "failure_reason", length = 512)
    private String failureReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version")
    private long version;

    protected PaymentTransaction() {
        // JPA
    }

    private PaymentTransaction(String idempotencyKey, UUID customerId, UUID bookingId, UUID providerId,
                               BigDecimal amount, BigDecimal platformFee, PaymentMethod method,
                               String gateway, String paymentCredentialEncrypted) {
        this.id = UUID.randomUUID();
        this.idempotencyKey = idempotencyKey;
        this.customerId = customerId;
        this.bookingId = bookingId;
        this.providerId = providerId;
        this.amount = amount;
        this.platformFee = platformFee;
        this.method = method;
        this.gateway = gateway;
        this.paymentCredentialEncrypted = paymentCredentialEncrypted;
        this.status = TransactionStatus.PENDING;
        this.refundedAmount = BigDecimal.ZERO;
        this.attemptCount = 1;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public static PaymentTransaction initiate(String idempotencyKey, UUID customerId, UUID bookingId,
                                              UUID providerId, BigDecimal amount, BigDecimal platformFee,
                                              PaymentMethod method, String gateway,
                                              String paymentCredentialEncrypted) {
        return new PaymentTransaction(idempotencyKey, customerId, bookingId, providerId, amount,
                platformFee, method, gateway, paymentCredentialEncrypted);
    }

    /**
     * Transitions to {@code target}, enforcing the state machine (Requirement 12.4, Property 12).
     *
     * @throws PaymentException with 409 semantics if the transition is not permitted.
     */
    public void transitionTo(TransactionStatus target) {
        if (!status.canTransitionTo(target)) {
            throw PaymentException.invalidTransition(
                    "Transaction " + id + " cannot transition from " + status + " to " + target
                            + "; permitted targets: " + status.permittedTargets());
        }
        this.status = target;
        this.updatedAt = Instant.now();
    }

    /** Records a failed attempt reason without changing state (used before a retry). */
    public void recordFailureReason(String reason) {
        this.failureReason = reason;
        this.updatedAt = Instant.now();
    }

    /**
     * Registers an additional customer retry attempt.
     *
     * @return the new attempt count.
     */
    public int registerRetryAttempt() {
        this.attemptCount++;
        this.updatedAt = Instant.now();
        return this.attemptCount;
    }

    /** @return the provider's net earning = amount - platformFee (Requirement 12.10). */
    public BigDecimal providerNetEarning() {
        return amount.subtract(platformFee);
    }

    /**
     * Applies a refund amount and moves to REFUNDED (full) or PARTIALLY_REFUNDED (partial),
     * enforcing the state machine (Requirement 12.7).
     */
    public void applyRefund(BigDecimal refundAmount) {
        if (refundAmount == null || refundAmount.signum() <= 0) {
            throw PaymentException.validation("Refund amount must be positive");
        }
        BigDecimal newRefunded = this.refundedAmount.add(refundAmount);
        if (newRefunded.compareTo(amount) > 0) {
            throw PaymentException.validation(
                    "Refund amount " + refundAmount + " would exceed the transaction amount " + amount);
        }
        TransactionStatus target = newRefunded.compareTo(amount) == 0
                ? TransactionStatus.REFUNDED
                : TransactionStatus.PARTIALLY_REFUNDED;
        transitionTo(target);
        this.refundedAmount = newRefunded;
    }

    public void setGatewayReference(String gatewayReference) {
        this.gatewayReference = gatewayReference;
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public UUID getBookingId() {
        return bookingId;
    }

    public UUID getProviderId() {
        return providerId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public BigDecimal getPlatformFee() {
        return platformFee;
    }

    public PaymentMethod getMethod() {
        return method;
    }

    public String getGateway() {
        return gateway;
    }

    public TransactionStatus getStatus() {
        return status;
    }

    public String getGatewayReference() {
        return gatewayReference;
    }

    public String getPaymentCredentialEncrypted() {
        return paymentCredentialEncrypted;
    }

    public BigDecimal getRefundedAmount() {
        return refundedAmount;
    }

    public int getAttemptCount() {
        return attemptCount;
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
