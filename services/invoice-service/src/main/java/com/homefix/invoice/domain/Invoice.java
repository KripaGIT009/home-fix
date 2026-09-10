package com.homefix.invoice.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A generated invoice (Requirement 13). One invoice corresponds to exactly one completed payment.
 *
 * <p>The {@link #invoiceNumber} is globally unique and follows {@code INV-YYYY-MM-NNNNNN}
 * (Requirement 13.4, Property 14); the uniqueness constraint is enforced at the database level.
 * The PDF bytes live in S3 (Requirement 13.2); only the object key is persisted here. The
 * {@link #generatedAt} timestamp anchors the 24-month retention window (Requirement 13.5).
 */
@Entity
@Table(name = "invoice")
public class Invoice {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "invoice_number", nullable = false, unique = true, updatable = false, length = 32)
    private String invoiceNumber;

    @Column(name = "booking_id", nullable = false, updatable = false)
    private UUID bookingId;

    /** Unique per payment so a redelivered PaymentCompleted event cannot produce a second invoice. */
    @Column(name = "payment_id", nullable = false, unique = true, updatable = false)
    private UUID paymentId;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @Column(name = "provider_id", nullable = false, updatable = false)
    private UUID providerId;

    /** S3 object key of the stored PDF (Requirement 13.2). */
    @Column(name = "s3_key", nullable = false, updatable = false, length = 512)
    private String s3Key;

    /** Gross amount paid by the customer; backs provider earnings statements (Requirement 13.6). */
    @Column(name = "gross_amount", nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal grossAmount;

    /** Platform fee deducted from the gross amount (Requirement 13.6). */
    @Column(name = "platform_fee", nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal platformFee;

    /** Provider net payout = gross - platform fee (Requirement 13.6). */
    @Column(name = "provider_net_earning", nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal providerNetEarning;

    @Column(name = "generated_at", nullable = false, updatable = false)
    private Instant generatedAt;

    protected Invoice() {
        // JPA
    }

    private Invoice(String invoiceNumber, UUID bookingId, UUID paymentId, UUID customerId,
                    UUID providerId, String s3Key, BigDecimal grossAmount, BigDecimal platformFee,
                    BigDecimal providerNetEarning, Instant generatedAt) {
        this.id = UUID.randomUUID();
        this.invoiceNumber = invoiceNumber;
        this.bookingId = bookingId;
        this.paymentId = paymentId;
        this.customerId = customerId;
        this.providerId = providerId;
        this.s3Key = s3Key;
        this.grossAmount = grossAmount;
        this.platformFee = platformFee;
        this.providerNetEarning = providerNetEarning;
        this.generatedAt = generatedAt;
    }

    public static Invoice create(String invoiceNumber, UUID bookingId, UUID paymentId, UUID customerId,
                                 UUID providerId, String s3Key, BigDecimal grossAmount,
                                 BigDecimal platformFee, BigDecimal providerNetEarning,
                                 Instant generatedAt) {
        return new Invoice(invoiceNumber, bookingId, paymentId, customerId, providerId, s3Key,
                grossAmount, platformFee, providerNetEarning, generatedAt);
    }

    public UUID getId() {
        return id;
    }

    public String getInvoiceNumber() {
        return invoiceNumber;
    }

    public UUID getBookingId() {
        return bookingId;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public UUID getProviderId() {
        return providerId;
    }

    public String getS3Key() {
        return s3Key;
    }

    public BigDecimal getGrossAmount() {
        return grossAmount;
    }

    public BigDecimal getPlatformFee() {
        return platformFee;
    }

    public BigDecimal getProviderNetEarning() {
        return providerNetEarning;
    }

    public Instant getGeneratedAt() {
        return generatedAt;
    }
}
