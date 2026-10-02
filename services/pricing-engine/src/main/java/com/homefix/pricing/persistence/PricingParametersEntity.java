package com.homefix.pricing.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.homefix.pricing.domain.PricingParameters;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Row of {@code pricing.pricing_parameters}: one Admin-configured parameter set per service
 * subcategory (Requirement 6.11).
 *
 * <p>Keyed by the subcategory id itself rather than a surrogate, because the subcategory is the
 * only identity the parameters have: the Admin API is a replace-by-subcategory {@code PUT}, and
 * every read is a lookup by subcategory. The subcategory belongs to the Service Catalog's schema,
 * so there is deliberately no foreign key across the service boundary.
 *
 * <p>Column scales are wider than the 2-dp money policy so the database never rounds what the
 * Admin entered: monetary amounts keep 4 dp, the fractional platform-fee and tax rates 6 dp, and
 * the multipliers 4 dp. Rounding to the money scale happens in the price calculation, not here.
 *
 * <p>No {@code @Version}: an Admin {@code PUT} replaces the whole set and carries no version, so
 * last-writer-wins is the intended semantics and optimistic locking would only turn a concurrent
 * replace into an error.
 */
@Entity
@Table(name = "pricing_parameters")
public class PricingParametersEntity {

    private static final int MONEY_PRECISION = 19;
    private static final int MONEY_SCALE = 4;

    @Id
    @Column(name = "subcategory_id", nullable = false, updatable = false)
    private UUID subcategoryId;

    @Column(name = "base_price", nullable = false, precision = MONEY_PRECISION, scale = MONEY_SCALE)
    private BigDecimal basePrice;

    @Column(name = "per_km_rate", precision = MONEY_PRECISION, scale = MONEY_SCALE)
    private BigDecimal perKmRate;

    @Column(name = "max_travel_charge", precision = MONEY_PRECISION, scale = MONEY_SCALE)
    private BigDecimal maxTravelCharge;

    @Column(name = "night_surcharge", precision = MONEY_PRECISION, scale = MONEY_SCALE)
    private BigDecimal nightSurcharge;

    @Column(name = "weekend_surcharge", precision = MONEY_PRECISION, scale = MONEY_SCALE)
    private BigDecimal weekendSurcharge;

    /** Fraction, e.g. {@code 0.15} for 15%. */
    @Column(name = "platform_fee_rate", precision = 9, scale = 6)
    private BigDecimal platformFeeRate;

    /** Fraction, e.g. {@code 0.18} for 18%. */
    @Column(name = "tax_rate", precision = 9, scale = 6)
    private BigDecimal taxRate;

    @Column(name = "emergency_multiplier", precision = 9, scale = 4)
    private BigDecimal emergencyMultiplier;

    @Column(name = "surge_multiplier", precision = 9, scale = 4)
    private BigDecimal surgeMultiplier;

    @Column(name = "override_floor", precision = MONEY_PRECISION, scale = MONEY_SCALE)
    private BigDecimal overrideFloor;

    @Column(name = "override_ceiling", precision = MONEY_PRECISION, scale = MONEY_SCALE)
    private BigDecimal overrideCeiling;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PricingParametersEntity() {
        // JPA
    }

    private PricingParametersEntity(UUID subcategoryId, Instant now) {
        this.subcategoryId = subcategoryId;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** A new, empty row for {@code subcategoryId}; populate it with {@link #replaceWith}. */
    static PricingParametersEntity newFor(UUID subcategoryId, Instant now) {
        return new PricingParametersEntity(subcategoryId, now);
    }

    /**
     * Replaces every parameter with the given set, as the Admin {@code PUT} does: a field the
     * Admin left null is stored as null, not kept from the previous version.
     */
    void replaceWith(PricingParameters p, Instant now) {
        if (!subcategoryId.equals(p.subcategoryId())) {
            throw new IllegalArgumentException("parameters for " + p.subcategoryId()
                    + " cannot replace the row for " + subcategoryId);
        }
        this.basePrice = p.basePrice();
        this.perKmRate = p.perKmRate();
        this.maxTravelCharge = p.maxTravelCharge();
        this.nightSurcharge = p.nightSurcharge();
        this.weekendSurcharge = p.weekendSurcharge();
        this.platformFeeRate = p.platformFeeRate();
        this.taxRate = p.taxRate();
        this.emergencyMultiplier = p.emergencyMultiplier();
        this.surgeMultiplier = p.surgeMultiplier();
        this.overrideFloor = p.overrideFloor();
        this.overrideCeiling = p.overrideCeiling();
        this.updatedAt = now;
    }

    PricingParameters toDomain() {
        return new PricingParameters(subcategoryId, basePrice, perKmRate, maxTravelCharge,
                nightSurcharge, weekendSurcharge, platformFeeRate, taxRate, emergencyMultiplier,
                surgeMultiplier, overrideFloor, overrideCeiling);
    }

    public UUID getSubcategoryId() {
        return subcategoryId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
