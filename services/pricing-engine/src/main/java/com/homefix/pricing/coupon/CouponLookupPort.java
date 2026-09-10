package com.homefix.pricing.coupon;

import java.util.Optional;

import com.homefix.pricing.domain.Coupon;

/**
 * Resolves a coupon by its code (Requirement 6.10). Modelled as a port so the persistence layer
 * is decoupled and mockable in unit tests.
 */
public interface CouponLookupPort {

    Optional<Coupon> findByCode(String code);
}
