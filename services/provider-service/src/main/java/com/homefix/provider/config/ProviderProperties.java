package com.homefix.provider.config;

import java.math.BigDecimal;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tunable provider-domain limits and thresholds (Requirements 4 and 14).
 *
 * <p>Defaults match the acceptance criteria; all values are overridable via configuration
 * so operations can tune limits without a code change.
 */
@ConfigurationProperties(prefix = "homefix.provider")
public class ProviderProperties {

    /** Maximum active service categories a provider may select (Requirement 4.3). */
    private int maxActiveCategories = 5;

    /** Maximum active subcategories per category (Requirement 4.3). */
    private int maxSubcategoriesPerCategory = 10;

    /** Minimum number of skill tags (Requirement 4.1). */
    private int minSkillTags = 1;

    /** Maximum number of skill tags (Requirement 4.1). */
    private int maxSkillTags = 20;

    /** Minimum years of experience (Requirement 4.1). */
    private int minYearsExperience = 0;

    /** Maximum years of experience (Requirement 4.1). */
    private int maxYearsExperience = 50;

    /** Minimum service radius in km (Requirement 4.1, 4.2). */
    private int minServiceRadiusKm = 1;

    /** Maximum service radius in km (Requirement 4.1, 4.2). */
    private int maxServiceRadiusKm = 100;

    /** Minimum settlement request amount (Requirement 14.2). */
    private BigDecimal minSettlementAmount = new BigDecimal("1.00");

    /** Aggregate rating below which a provider is auto-flagged for review (Requirement 4.7). */
    private BigDecimal reviewRatingThreshold = new BigDecimal("3.0");

    /** ISO 4217 currency the wallet, earnings and settlement amounts are denominated in. */
    private String currency = "INR";

    /** Zone whose civil day bounds the dashboard's "today" earnings window (Requirement 14.1). */
    private String earningsDayZone = "Asia/Kolkata";

    public int getMaxActiveCategories() {
        return maxActiveCategories;
    }

    public void setMaxActiveCategories(int maxActiveCategories) {
        this.maxActiveCategories = maxActiveCategories;
    }

    public int getMaxSubcategoriesPerCategory() {
        return maxSubcategoriesPerCategory;
    }

    public void setMaxSubcategoriesPerCategory(int maxSubcategoriesPerCategory) {
        this.maxSubcategoriesPerCategory = maxSubcategoriesPerCategory;
    }

    public int getMinSkillTags() {
        return minSkillTags;
    }

    public void setMinSkillTags(int minSkillTags) {
        this.minSkillTags = minSkillTags;
    }

    public int getMaxSkillTags() {
        return maxSkillTags;
    }

    public void setMaxSkillTags(int maxSkillTags) {
        this.maxSkillTags = maxSkillTags;
    }

    public int getMinYearsExperience() {
        return minYearsExperience;
    }

    public void setMinYearsExperience(int minYearsExperience) {
        this.minYearsExperience = minYearsExperience;
    }

    public int getMaxYearsExperience() {
        return maxYearsExperience;
    }

    public void setMaxYearsExperience(int maxYearsExperience) {
        this.maxYearsExperience = maxYearsExperience;
    }

    public int getMinServiceRadiusKm() {
        return minServiceRadiusKm;
    }

    public void setMinServiceRadiusKm(int minServiceRadiusKm) {
        this.minServiceRadiusKm = minServiceRadiusKm;
    }

    public int getMaxServiceRadiusKm() {
        return maxServiceRadiusKm;
    }

    public void setMaxServiceRadiusKm(int maxServiceRadiusKm) {
        this.maxServiceRadiusKm = maxServiceRadiusKm;
    }

    public BigDecimal getMinSettlementAmount() {
        return minSettlementAmount;
    }

    public void setMinSettlementAmount(BigDecimal minSettlementAmount) {
        this.minSettlementAmount = minSettlementAmount;
    }

    public BigDecimal getReviewRatingThreshold() {
        return reviewRatingThreshold;
    }

    public void setReviewRatingThreshold(BigDecimal reviewRatingThreshold) {
        this.reviewRatingThreshold = reviewRatingThreshold;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public String getEarningsDayZone() {
        return earningsDayZone;
    }

    public void setEarningsDayZone(String earningsDayZone) {
        this.earningsDayZone = earningsDayZone;
    }
}
