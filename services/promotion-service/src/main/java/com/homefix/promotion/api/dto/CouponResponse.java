package com.homefix.promotion.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import com.homefix.promotion.domain.Coupon;
import com.homefix.promotion.domain.DiscountType;

/**
 * Read model for a coupon returned by the Admin CRUD endpoints (Requirement 21.1). Carries only
 * coupon definition and counter state — no customer PII (Requirement 26.4).
 */
public record CouponResponse(
        UUID id,
        String code,
        DiscountType discountType,
        BigDecimal discountValue,
        BigDecimal minOrderValue,
        BigDecimal maxDiscountCap,
        LocalDate validFrom,
        LocalDate expiryDate,
        int perUserLimit,
        int totalLimit,
        int totalUsed,
        boolean active) {

    public static CouponResponse from(Coupon coupon) {
        return new CouponResponse(
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
                coupon.isActive());
    }
}
