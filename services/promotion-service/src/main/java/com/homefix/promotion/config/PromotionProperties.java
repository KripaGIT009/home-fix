package com.homefix.promotion.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tunable promotion-domain limits (Requirement 21).
 *
 * <p>Defaults match the acceptance criteria; all values are overridable via configuration so
 * operations can tune limits without a code change.
 */
@ConfigurationProperties(prefix = "homefix.promotion")
public class PromotionProperties {

    /**
     * Maximum number of optimistic-lock retries when concurrent redemption or cancellation
     * requests collide on the same coupon row before the request is rejected with a 409
     * (Requirement 21.3, Property 20).
     */
    private int maxRedemptionRetries = 5;

    public int getMaxRedemptionRetries() {
        return maxRedemptionRetries;
    }

    public void setMaxRedemptionRetries(int maxRedemptionRetries) {
        this.maxRedemptionRetries = maxRedemptionRetries;
    }
}
