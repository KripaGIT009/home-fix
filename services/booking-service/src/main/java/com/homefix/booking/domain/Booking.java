package com.homefix.booking.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * Aggregate root for a customer's service request (design {@code BOOKING}).
 *
 * <p>The status is driven exclusively through the {@link BookingStateMachine}; callers must
 * not mutate it directly outside {@code applyTransition}. Every status change is mirrored by
 * a {@link BookingAudit} row so the audit trail forms a contiguous chain (Property 9).
 */
@Entity
@Table(name = "booking", indexes = {
        @Index(name = "idx_booking_reference", columnList = "reference", unique = true),
        @Index(name = "idx_booking_customer", columnList = "customer_id"),
        // Customer service history: newest-first page by customer (migration V2).
        @Index(name = "idx_booking_customer_created", columnList = "customer_id, created_at DESC")
})
public class Booking {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** Human-facing unique booking reference number (Requirement 7.1). */
    @Column(name = "reference", nullable = false, updatable = false, length = 40)
    private String reference;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @Column(name = "provider_id")
    private UUID providerId;

    @Column(name = "category_id", nullable = false, updatable = false)
    private UUID categoryId;

    @Column(name = "subcategory_id", nullable = false, updatable = false)
    private UUID subcategoryId;

    @Column(name = "address_id")
    private UUID addressId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 40)
    private BookingStatus status;

    @Column(name = "is_emergency", nullable = false)
    private boolean emergency;

    @Column(name = "scheduled_at")
    private Instant scheduledAt;

    @Column(name = "estimated_total", precision = 12, scale = 2)
    private BigDecimal estimatedTotal;

    @Column(name = "final_total", precision = 12, scale = 2)
    private BigDecimal finalTotal;

    /** Cancellation fee actually charged when the booking is cancelled (Requirement 9.16-9.18). */
    @Column(name = "cancellation_fee", precision = 12, scale = 2)
    private BigDecimal cancellationFee;

    @Column(name = "saga_state", length = 60)
    private String sagaState;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "net_duration_seconds")
    private Integer netDurationSeconds;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected Booking() {
        // JPA
    }

    private Booking(UUID id, String reference, UUID customerId, UUID categoryId, UUID subcategoryId,
                    UUID addressId, boolean emergency, Instant scheduledAt, BigDecimal estimatedTotal) {
        this.id = id;
        this.reference = reference;
        this.customerId = customerId;
        this.categoryId = categoryId;
        this.subcategoryId = subcategoryId;
        this.addressId = addressId;
        this.emergency = emergency;
        this.scheduledAt = scheduledAt;
        this.estimatedTotal = estimatedTotal;
        this.status = BookingStatus.CREATED;
        this.createdAt = Instant.now();
    }

    /**
     * Creates a booking in {@link BookingStatus#CREATED} with a fresh id and the supplied
     * unique reference.
     */
    public static Booking create(String reference, UUID customerId, UUID categoryId,
                                 UUID subcategoryId, UUID addressId, boolean emergency,
                                 Instant scheduledAt, BigDecimal estimatedTotal) {
        return new Booking(UUID.randomUUID(), reference, customerId, categoryId, subcategoryId,
                addressId, emergency, scheduledAt, estimatedTotal);
    }

    /**
     * Applies a validated status change. Callers MUST verify the transition against the
     * {@link BookingStateMachine} first (see {@code BookingTransitionService}); this method
     * only stores the new state and performs no validation itself.
     */
    public void applyStatus(BookingStatus status) {
        this.status = status;
    }

    public UUID getId() {
        return id;
    }

    public String getReference() {
        return reference;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public UUID getProviderId() {
        return providerId;
    }

    public void setProviderId(UUID providerId) {
        this.providerId = providerId;
    }

    public UUID getCategoryId() {
        return categoryId;
    }

    public UUID getSubcategoryId() {
        return subcategoryId;
    }

    public UUID getAddressId() {
        return addressId;
    }

    public BookingStatus getStatus() {
        return status;
    }

    public boolean isEmergency() {
        return emergency;
    }

    public Instant getScheduledAt() {
        return scheduledAt;
    }

    public BigDecimal getEstimatedTotal() {
        return estimatedTotal;
    }

    public BigDecimal getFinalTotal() {
        return finalTotal;
    }

    public void setFinalTotal(BigDecimal finalTotal) {
        this.finalTotal = finalTotal;
    }

    public BigDecimal getCancellationFee() {
        return cancellationFee;
    }

    public void setCancellationFee(BigDecimal cancellationFee) {
        this.cancellationFee = cancellationFee;
    }

    public String getSagaState() {
        return sagaState;
    }

    public void setSagaState(String sagaState) {
        this.sagaState = sagaState;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    public Integer getNetDurationSeconds() {
        return netDurationSeconds;
    }

    public void setNetDurationSeconds(Integer netDurationSeconds) {
        this.netDurationSeconds = netDurationSeconds;
    }

    public long getVersion() {
        return version;
    }
}
