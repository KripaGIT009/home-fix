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
 * An itemised earnings-history entry for a provider (Requirement 14.1, 14.5, mirrors the
 * {@code PROVIDER_EARNINGS} data model).
 *
 * <p>Each entry records the booking reference, gross earning, platform fee deducted, and net
 * earning for a job credit, or an itemised deduction (platform fee / penalty). The
 * {@code type} distinguishes credits from deductions so history can be reported per-line.
 */
@Entity
@Table(name = "provider_earning")
public class ProviderEarning {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "provider_id", nullable = false)
    private UUID providerId;

    @Column(name = "booking_id")
    private UUID bookingId;

    @Column(name = "booking_reference", length = 64)
    private String bookingReference;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 32)
    private EarningType type;

    @Column(name = "gross", nullable = false, precision = 12, scale = 2)
    private BigDecimal gross = BigDecimal.ZERO;

    @Column(name = "platform_fee", nullable = false, precision = 12, scale = 2)
    private BigDecimal platformFee = BigDecimal.ZERO;

    @Column(name = "net", nullable = false, precision = 12, scale = 2)
    private BigDecimal net = BigDecimal.ZERO;

    @Column(name = "credited_at", nullable = false)
    private Instant creditedAt;

    protected ProviderEarning() {
        // JPA
    }

    private ProviderEarning(UUID providerId, UUID bookingId, String bookingReference,
                            EarningType type, BigDecimal gross, BigDecimal platformFee, BigDecimal net) {
        this.id = UUID.randomUUID();
        this.providerId = providerId;
        this.bookingId = bookingId;
        this.bookingReference = bookingReference;
        this.type = type;
        this.gross = gross;
        this.platformFee = platformFee;
        this.net = net;
        this.creditedAt = Instant.now();
    }

    /**
     * A completed-job credit line: gross earning, platform fee deducted, resulting net.
     * The net (= gross − platformFee) is what is added to the wallet.
     */
    public static ProviderEarning jobCredit(UUID providerId, UUID bookingId, String bookingReference,
                                            BigDecimal gross, BigDecimal platformFee) {
        BigDecimal net = gross.subtract(platformFee);
        return new ProviderEarning(providerId, bookingId, bookingReference,
                EarningType.JOB_CREDIT, gross, platformFee, net);
    }

    /** A standalone penalty deduction line (Requirement 14.1). */
    public static ProviderEarning penalty(UUID providerId, UUID bookingId, String bookingReference,
                                          BigDecimal amount) {
        return new ProviderEarning(providerId, bookingId, bookingReference,
                EarningType.PENALTY_DEDUCTION, BigDecimal.ZERO, BigDecimal.ZERO, amount.negate());
    }

    public UUID getId() {
        return id;
    }

    public UUID getProviderId() {
        return providerId;
    }

    public UUID getBookingId() {
        return bookingId;
    }

    public String getBookingReference() {
        return bookingReference;
    }

    public EarningType getType() {
        return type;
    }

    public BigDecimal getGross() {
        return gross;
    }

    public BigDecimal getPlatformFee() {
        return platformFee;
    }

    public BigDecimal getNet() {
        return net;
    }

    public Instant getCreditedAt() {
        return creditedAt;
    }
}
