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
 * A provider settlement bank transfer processed by the Payment Service (Requirement 14.3-14.4).
 *
 * <p>Created in {@link SettlementStatus#PENDING} and advanced through the settlement state machine.
 * The destination bank account reference is stored encrypted at rest (Requirement 12.9 / 4.9).
 */
@Entity
@Table(name = "payment_settlement")
public class Settlement {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "provider_id", nullable = false, updatable = false)
    private UUID providerId;

    @Column(name = "amount", nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private SettlementStatus status;

    /** KMS/AES ciphertext of the destination bank account reference. */
    @Column(name = "bank_account_ref_encrypted", nullable = false)
    private String bankAccountRefEncrypted;

    @Column(name = "gateway_reference", length = 128)
    private String gatewayReference;

    @Column(name = "failure_reason", length = 512)
    private String failureReason;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private Instant requestedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version")
    private long version;

    protected Settlement() {
        // JPA
    }

    private Settlement(UUID providerId, BigDecimal amount, String bankAccountRefEncrypted) {
        this.id = UUID.randomUUID();
        this.providerId = providerId;
        this.amount = amount;
        this.bankAccountRefEncrypted = bankAccountRefEncrypted;
        this.status = SettlementStatus.PENDING;
        this.requestedAt = Instant.now();
        this.updatedAt = this.requestedAt;
    }

    public static Settlement initiate(UUID providerId, BigDecimal amount, String bankAccountRefEncrypted) {
        return new Settlement(providerId, amount, bankAccountRefEncrypted);
    }

    public void transitionTo(SettlementStatus target) {
        if (!status.canTransitionTo(target)) {
            throw PaymentException.invalidTransition(
                    "Settlement " + id + " cannot transition from " + status + " to " + target);
        }
        this.status = target;
        this.updatedAt = Instant.now();
    }

    public void recordFailure(String reason) {
        this.failureReason = reason;
        this.updatedAt = Instant.now();
    }

    public void setGatewayReference(String gatewayReference) {
        this.gatewayReference = gatewayReference;
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getProviderId() {
        return providerId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public SettlementStatus getStatus() {
        return status;
    }

    public String getBankAccountRefEncrypted() {
        return bankAccountRefEncrypted;
    }

    public String getGatewayReference() {
        return gatewayReference;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public Instant getRequestedAt() {
        return requestedAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
