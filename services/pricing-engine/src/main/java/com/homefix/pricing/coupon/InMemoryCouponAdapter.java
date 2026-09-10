package com.homefix.pricing.coupon;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.homefix.pricing.domain.Coupon;

/**
 * Default in-memory {@link CouponLookupPort} and {@link CouponUsagePort} so the service boots
 * and is usable in local/dev environments and tests without a provisioned database. In
 * production these are replaced by JPA-backed adapters reading the coupon and redemption
 * tables.
 */
@Component
public class InMemoryCouponAdapter implements CouponLookupPort, CouponUsagePort {

    private final Map<String, Coupon> coupons = new ConcurrentHashMap<>();
    private final Map<String, Integer> totalRedemptions = new ConcurrentHashMap<>();
    private final Map<String, Integer> userRedemptions = new ConcurrentHashMap<>();

    @Override
    public Optional<Coupon> findByCode(String code) {
        return Optional.ofNullable(coupons.get(code));
    }

    @Override
    public int totalRedemptions(String couponCode) {
        return totalRedemptions.getOrDefault(couponCode, 0);
    }

    @Override
    public int userRedemptions(String couponCode, UUID userId) {
        return userRedemptions.getOrDefault(couponCode + ":" + userId, 0);
    }
}
