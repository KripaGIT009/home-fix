package com.homefix.pricing.config;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.ZoneId;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tunable Pricing Engine limits, multiplier caps, rounding policy and cache settings
 * (Requirement 6).
 *
 * <p>Defaults match the acceptance criteria; all values are overridable via configuration so
 * operations can tune the engine without a code change. These are platform-wide guardrails —
 * the per-subcategory economic parameters (base price, per-km rate, surcharges, platform fee)
 * live in {@code PricingParameters} and are Admin-configurable and cached (Requirement 6.11).
 */
@ConfigurationProperties(prefix = "homefix.pricing")
public class PricingProperties {

    /** Maximum emergency multiplier factor (Requirement 6.2, default 2.0x). */
    private BigDecimal maxEmergencyMultiplier = new BigDecimal("2.0");

    /** Maximum surge multiplier factor (Requirement 6.3, default 2.0x). */
    private BigDecimal maxSurgeMultiplier = new BigDecimal("2.0");

    /** Minimum permissible final total (Requirement 6.1). */
    private BigDecimal minTotal = new BigDecimal("0.01");

    /** Scale (number of decimal places) applied to all monetary values. */
    private int moneyScale = 2;

    /** Rounding mode applied to all monetary values. */
    private RoundingMode moneyRounding = RoundingMode.HALF_UP;

    /**
     * Admin pricing-parameter cache time-to-live (Requirement 6.11). Updated parameters are
     * applied to new bookings within this bound. Defaults to 60 seconds.
     */
    private Duration configCacheTtl = Duration.ofSeconds(60);

    /** Local timezone used to evaluate the night (22:00–06:00) and weekend surcharge windows. */
    private ZoneId zoneId = ZoneId.of("UTC");

    public BigDecimal getMaxEmergencyMultiplier() {
        return maxEmergencyMultiplier;
    }

    public void setMaxEmergencyMultiplier(BigDecimal maxEmergencyMultiplier) {
        this.maxEmergencyMultiplier = maxEmergencyMultiplier;
    }

    public BigDecimal getMaxSurgeMultiplier() {
        return maxSurgeMultiplier;
    }

    public void setMaxSurgeMultiplier(BigDecimal maxSurgeMultiplier) {
        this.maxSurgeMultiplier = maxSurgeMultiplier;
    }

    public BigDecimal getMinTotal() {
        return minTotal;
    }

    public void setMinTotal(BigDecimal minTotal) {
        this.minTotal = minTotal;
    }

    public int getMoneyScale() {
        return moneyScale;
    }

    public void setMoneyScale(int moneyScale) {
        this.moneyScale = moneyScale;
    }

    public RoundingMode getMoneyRounding() {
        return moneyRounding;
    }

    public void setMoneyRounding(RoundingMode moneyRounding) {
        this.moneyRounding = moneyRounding;
    }

    public Duration getConfigCacheTtl() {
        return configCacheTtl;
    }

    public void setConfigCacheTtl(Duration configCacheTtl) {
        this.configCacheTtl = configCacheTtl;
    }

    public ZoneId getZoneId() {
        return zoneId;
    }

    public void setZoneId(ZoneId zoneId) {
        this.zoneId = zoneId;
    }
}
