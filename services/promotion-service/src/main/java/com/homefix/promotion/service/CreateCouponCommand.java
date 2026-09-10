package com.homefix.promotion.service;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.homefix.promotion.domain.DiscountType;

/**
 * Command carrying the attributes needed to create a coupon (Requirement 21.1). Validation of the
 * attributes is performed by the domain layer ({@code CouponSpecs}) when the {@code Coupon} is
 * created, so this is a plain value carrier.
 */
public record CreateCouponCommand(
        String code,
        DiscountType discountType,
        BigDecimal discountValue,
        BigDecimal minOrderValue,
        BigDecimal maxDiscountCap,
        LocalDate validFrom,
        LocalDate expiryDate,
        int perUserLimit,
        int totalLimit) {
}
