package com.homefix.provider.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A provider settlement request (Requirement 14.2, mirrors the {@code SETTLEMENT} data model).
 *
 * <p>Created in {@code PENDING} status once validated. The bank account reference is stored
 * encrypted at rest (Requirement 4.9). The actual bank transfer is performed downstream by
 * the Payment Service (Requirement 14.3), which advances the status.
 */
@Entity
@Table(name = "settlement")
public class Settlement {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "provider_id", nullable = false)
    private UUID providerId;

    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private SettlementStatus status;

    /** KMS/AES ciphertext of the destination bank account reference (Requirement 4.9). */
    @Column(name = "bank_account_ref_encrypted", nullable = false)
    private String bankAccountRefEncrypted;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private Instant requestedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

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
    }

    public static Settlement request(UUID providerId, BigDecimal amount, String bankAccountRefEncrypted) {
        return new Settlement(providerId, amount, bankAccountRefEncrypted);
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

    public Instant getRequestedAt() {
        return requestedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }
}
