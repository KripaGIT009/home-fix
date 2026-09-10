package com.homefix.pricing.coupon;

import java.util.UUID;

/**
 * Read model for coupon usage counts, consulted when validating a coupon's usage limits
 * (Requirement 6.10).
 *
 * <p>Modelled as a port so the usage-limit lookups are mockable in unit tests and decoupled
 * from the persistence layer that records redemptions.
 */
public interface CouponUsagePort {

    /** @return how many times the given coupon code has been redeemed platform-wide. */
    int totalRedemptions(String couponCode);

    /** @return how many times the given user has redeemed the given coupon code. */
    int userRedemptions(String couponCode, UUID userId);
}
