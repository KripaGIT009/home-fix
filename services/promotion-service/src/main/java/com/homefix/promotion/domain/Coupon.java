package com.homefix.promotion.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import com.homefix.promotion.service.CouponException.ConstraintCode;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * A coupon aggregate (Requirement 21).
 *
 * <p>Holds the coupon definition (code, discount type/value, minimum order value, maximum discount
 * cap, validity window, per-user and total usage limits) plus the mutable {@code totalUsed} counter
 * and {@code active} flag. The {@link Version} column provides optimistic locking so concurrent
 * redemptions serialise on the same row and can never over-increment past the total limit
 * (Requirement 21.3, Property 20).
 *
 * <p>Codes are normalised to upper case so uniqueness and lookups are case-insensitive
 * (Requirement 21.1). Monetary values use {@link BigDecimal}.
 */
@Entity
@Table(name = "coupon")
public class Coupon {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** Normalised (upper-case) coupon code; unique so lookups are case-insensitive. */
    @Column(name = "code", nullable = false, unique = true, updatable = false, length = 20)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(name = "discount_type", nullable = false, length = 16)
    private DiscountType discountType;

    @Column(name = "discount_value", nullable = false, precision = 12, scale = 2)
    private BigDecimal discountValue;

    @Column(name = "min_order_value", nullable = false, precision = 12, scale = 2)
    private BigDecimal minOrderValue;

    /** Required for PERCENTAGE coupons; {@code null} for FLAT (Requirement 21.1). */
    @Column(name = "max_discount_cap", precision = 12, scale = 2)
    private BigDecimal maxDiscountCap;

    @Column(name = "valid_from", nullable = false)
    private LocalDate validFrom;

    @Column(name = "expiry_date", nullable = false)
    private LocalDate expiryDate;

    @Column(name = "per_user_limit", nullable = false)
    private int perUserLimit;

    @Column(name = "total_limit", nullable = false)
    private int totalLimit;

    @Column(name = "total_used", nullable = false)
    private int totalUsed;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version")
    private long version;

    protected Coupon() {
        // JPA
    }

    private Coupon(String code, DiscountType discountType, BigDecimal discountValue,
                   BigDecimal minOrderValue, BigDecimal maxDiscountCap, LocalDate validFrom,
                   LocalDate expiryDate, int perUserLimit, int totalLimit) {
        this.id = UUID.randomUUID();
        this.code = normaliseCode(code);
        this.discountType = discountType;
        this.discountValue = discountValue;
        this.minOrderValue = minOrderValue;
        this.maxDiscountCap = maxDiscountCap;
        this.validFrom = validFrom;
        this.expiryDate = expiryDate;
        this.perUserLimit = perUserLimit;
        this.totalLimit = totalLimit;
        this.totalUsed = 0;
        this.active = true;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    /**
     * Factory validating and creating a coupon (Requirement 21.1). All attribute constraints are
     * enforced here so an invalid coupon can never be persisted.
     */
    public static Coupon create(String code, DiscountType discountType, BigDecimal discountValue,
                                BigDecimal minOrderValue, BigDecimal maxDiscountCap,
                                LocalDate validFrom, LocalDate expiryDate,
                                int perUserLimit, int totalLimit) {
        CouponSpecs.validateAttributes(code, discountType, discountValue, minOrderValue,
                maxDiscountCap, validFrom, expiryDate, perUserLimit, totalLimit);
        return new Coupon(code, discountType, discountValue, minOrderValue, maxDiscountCap,
                validFrom, expiryDate, perUserLimit, totalLimit);
    }

    /** Normalises a raw code to the canonical stored form: trimmed and upper-cased. */
    public static String normaliseCode(String rawCode) {
        return rawCode == null ? null : rawCode.trim().toUpperCase(java.util.Locale.ROOT);
    }

    /**
     * Evaluates all checkout constraints for the supplied context, returning the first violated
     * constraint or empty if the coupon is fully applicable (Requirement 21.2). Pure and
     * side-effect-free so it is directly unit-testable.
     *
     * @param orderValue         the order value at checkout.
     * @param currentUserUsage   how many times this user has already redeemed the coupon.
     * @param today              the current local date used for the validity-window check.
     * @return the first {@link ConstraintCode} that fails, or empty if applicable.
     */
    public Optional<ConstraintCode> checkApplicability(BigDecimal orderValue, int currentUserUsage,
                                                       LocalDate today) {
        if (!active) {
            return Optional.of(ConstraintCode.COUPON_INACTIVE);
        }
        if (today.isBefore(validFrom)) {
            return Optional.of(ConstraintCode.COUPON_NOT_STARTED);
        }
        if (today.isAfter(expiryDate)) {
            return Optional.of(ConstraintCode.COUPON_EXPIRED);
        }
        if (orderValue == null || orderValue.compareTo(minOrderValue) < 0) {
            return Optional.of(ConstraintCode.MIN_ORDER_VALUE_NOT_MET);
        }
        if (currentUserUsage >= perUserLimit) {
            return Optional.of(ConstraintCode.PER_USER_LIMIT_REACHED);
        }
        if (totalUsed >= totalLimit) {
            return Optional.of(ConstraintCode.TOTAL_LIMIT_REACHED);
        }
        return Optional.empty();
    }

    /**
     * Increments the total usage counter by one, enforcing the total limit (Requirement 21.3).
     *
     * @return {@code true} if the increment was applied; {@code false} if it would exceed the total
     *         limit (in which case the counter is left unchanged).
     */
    public boolean incrementTotalUsed() {
        if (totalUsed >= totalLimit) {
            return false;
        }
        totalUsed++;
        this.updatedAt = Instant.now();
        return true;
    }

    /**
     * Decrements the total usage counter by one, never going below zero (Requirement 21.5). Used
     * when a coupon-bearing booking is cancelled before payment capture.
     */
    public void decrementTotalUsed() {
        if (totalUsed > 0) {
            totalUsed--;
            this.updatedAt = Instant.now();
        }
    }

    /** Immediately prevents further redemptions (Requirement 21.4). */
    public void deactivate() {
        this.active = false;
        this.updatedAt = Instant.now();
    }

    public void activate() {
        this.active = true;
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public DiscountType getDiscountType() {
        return discountType;
    }

    public BigDecimal getDiscountValue() {
        return discountValue;
    }

    public BigDecimal getMinOrderValue() {
        return minOrderValue;
    }

    public BigDecimal getMaxDiscountCap() {
        return maxDiscountCap;
    }

    public LocalDate getValidFrom() {
        return validFrom;
    }

    public LocalDate getExpiryDate() {
        return expiryDate;
    }

    public int getPerUserLimit() {
        return perUserLimit;
    }

    public int getTotalLimit() {
        return totalLimit;
    }

    public int getTotalUsed() {
        return totalUsed;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getVersion() {
        return version;
    }
}
