package com.homefix.catalog.config;

import java.math.BigDecimal;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tunable catalog-domain limits and cache settings (Requirement 3).
 *
 * <p>Defaults match the acceptance criteria; all values are overridable via configuration so
 * operations can tune limits without a code change.
 */
@ConfigurationProperties(prefix = "homefix.catalog")
public class CatalogProperties {

    /** Minimum subcategory base price (Requirement 3.7). */
    private BigDecimal minBasePrice = new BigDecimal("0.01");

    /** Maximum subcategory base price (Requirement 3.7). */
    private BigDecimal maxBasePrice = new BigDecimal("999999.99");

    /** Minimum estimated duration in minutes (Requirement 3.7). */
    private int minDurationMinutes = 1;

    /** Maximum estimated duration in minutes (Requirement 3.7). */
    private int maxDurationMinutes = 480;

    /** Maximum number of skill tags per subcategory (Requirement 3.7). */
    private int maxSkillTags = 20;

    /**
     * Read-through cache time-to-live (Requirement 3.8). Catalog responses may be served from
     * cache for at most this long, so served data reflects DB state as of no more than this
     * duration ago. Defaults to 300 seconds.
     */
    private Duration cacheTtl = Duration.ofSeconds(300);

    public BigDecimal getMinBasePrice() {
        return minBasePrice;
    }

    public void setMinBasePrice(BigDecimal minBasePrice) {
        this.minBasePrice = minBasePrice;
    }

    public BigDecimal getMaxBasePrice() {
        return maxBasePrice;
    }

    public void setMaxBasePrice(BigDecimal maxBasePrice) {
        this.maxBasePrice = maxBasePrice;
    }

    public int getMinDurationMinutes() {
        return minDurationMinutes;
    }

    public void setMinDurationMinutes(int minDurationMinutes) {
        this.minDurationMinutes = minDurationMinutes;
    }

    public int getMaxDurationMinutes() {
        return maxDurationMinutes;
    }

    public void setMaxDurationMinutes(int maxDurationMinutes) {
        this.maxDurationMinutes = maxDurationMinutes;
    }

    public int getMaxSkillTags() {
        return maxSkillTags;
    }

    public void setMaxSkillTags(int maxSkillTags) {
        this.maxSkillTags = maxSkillTags;
    }

    public Duration getCacheTtl() {
        return cacheTtl;
    }

    public void setCacheTtl(Duration cacheTtl) {
        this.cacheTtl = cacheTtl;
    }
}
