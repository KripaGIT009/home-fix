package com.homefix.promotion.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.homefix.promotion.domain.Coupon;
import com.homefix.promotion.domain.CouponStatus;
import com.homefix.promotion.domain.DiscountType;

/**
 * A coupon as the Admin Portal reads it ({@code Coupon} in {@code features/coupons/api.ts},
 * Requirement 19.2). Differs from {@link CouponResponse} only in naming and derivation:
 * {@code totalRedeemed} is the stored {@code totalUsed} counter (21.3), and {@code status} is the
 * {@link CouponStatus} derived from the active flag and expiry date, which the caller computes on
 * the service clock. {@code maxDiscountCap} is optional in the portal and absent for FLAT coupons,
 * so it is omitted when null. No customer PII (Requirement 26.4).
 */
public record AdminCouponResponse(
        UUID id,
        String code,
        DiscountType discountType,
        BigDecimal discountValue,
        BigDecimal minOrderValue,
        @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal maxDiscountCap,
        LocalDate validFrom,
        LocalDate expiryDate,
        int perUserLimit,
        int totalLimit,
        int totalRedeemed,
        CouponStatus status) {

    public static AdminCouponResponse from(Coupon coupon, CouponStatus status) {
        return new AdminCouponResponse(
                coupon.getId(),
                coupon.getCode(),
                coupon.getDiscountType(),
                coupon.getDiscountValue(),
                coupon.getMinOrderValue(),
                coupon.getMaxDiscountCap(),
                coupon.getValidFrom(),
                coupon.getExpiryDate(),
                coupon.getPerUserLimit(),
                coupon.getTotalLimit(),
                coupon.getTotalUsed(),
                status);
    }
}
