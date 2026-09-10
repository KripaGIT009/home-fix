package com.homefix.promotion.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

/**
 * Per-user redemption counter for a coupon (Requirement 21.3, 21.5). One row per
 * {@code (couponId, userId)} pair; the unique constraint plus the {@link Version} column ensure a
 * user's counter cannot be over-incremented past the per-user limit under concurrency (Property 20).
 */
@Entity
@Table(name = "coupon_usage",
        uniqueConstraints = @UniqueConstraint(name = "uk_coupon_usage_coupon_user",
                columnNames = {"coupon_id", "user_id"}))
public class CouponUsage {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "coupon_id", nullable = false, updatable = false)
    private UUID couponId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "usage_count", nullable = false)
    private int usageCount;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version")
    private long version;

    protected CouponUsage() {
        // JPA
    }

    private CouponUsage(UUID couponId, UUID userId) {
        this.id = UUID.randomUUID();
        this.couponId = couponId;
        this.userId = userId;
        this.usageCount = 0;
        this.updatedAt = Instant.now();
    }

    public static CouponUsage forUser(UUID couponId, UUID userId) {
        return new CouponUsage(couponId, userId);
    }

    /**
     * Increments this user's usage counter, enforcing {@code perUserLimit} (Requirement 21.3).
     *
     * @return {@code true} if applied; {@code false} if it would exceed the per-user limit (counter
     *         left unchanged).
     */
    public boolean increment(int perUserLimit) {
        if (usageCount >= perUserLimit) {
            return false;
        }
        usageCount++;
        this.updatedAt = Instant.now();
        return true;
    }

    /** Decrements this user's usage counter, never below zero (Requirement 21.5). */
    public void decrement() {
        if (usageCount > 0) {
            usageCount--;
            this.updatedAt = Instant.now();
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getCouponId() {
        return couponId;
    }

    public UUID getUserId() {
        return userId;
    }

    public int getUsageCount() {
        return usageCount;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getVersion() {
        return version;
    }
}
