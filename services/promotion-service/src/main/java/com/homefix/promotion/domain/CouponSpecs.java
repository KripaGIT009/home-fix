package com.homefix.promotion.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.regex.Pattern;

import com.homefix.promotion.service.CouponException;

/**
 * Pure attribute-validation rules for coupon creation (Requirement 21.1). Factored out of the
 * entity/service so the rule set is a single source of truth and is directly unit-testable.
 *
 * <p>Each rule throws a {@link CouponException#validation(String)} (HTTP 400) with a descriptive
 * message identifying the offending attribute.
 */
public final class CouponSpecs {

    /** Coupon codes are 4–20 alphanumeric characters, case-insensitive (Requirement 21.1). */
    private static final Pattern CODE_PATTERN = Pattern.compile("^[A-Za-z0-9]{4,20}$");

    private CouponSpecs() {
    }

    /**
     * Validates all coupon attributes (Requirement 21.1), throwing {@link CouponException} on the
     * first violation.
     */
    public static void validateAttributes(String code, DiscountType discountType,
                                          BigDecimal discountValue, BigDecimal minOrderValue,
                                          BigDecimal maxDiscountCap, LocalDate validFrom,
                                          LocalDate expiryDate, int perUserLimit, int totalLimit) {
        if (code == null || !CODE_PATTERN.matcher(code.trim()).matches()) {
            throw CouponException.validation(
                    "Coupon code must be 4-20 alphanumeric characters");
        }
        if (discountType == null) {
            throw CouponException.validation("Discount type is required (FLAT or PERCENTAGE)");
        }
        if (discountValue == null || discountValue.signum() <= 0) {
            throw CouponException.validation("Discount value must be greater than 0");
        }
        if (minOrderValue == null || minOrderValue.signum() < 0) {
            throw CouponException.validation("Minimum order value must be 0 or greater");
        }
        if (discountType == DiscountType.PERCENTAGE) {
            if (maxDiscountCap == null || maxDiscountCap.signum() <= 0) {
                throw CouponException.validation(
                        "A maximum discount cap greater than 0 is required for PERCENTAGE coupons");
            }
        }
        if (validFrom == null || expiryDate == null) {
            throw CouponException.validation("valid_from and expiry dates are required");
        }
        if (!expiryDate.isAfter(validFrom)) {
            throw CouponException.validation("Expiry date must be after the valid_from date");
        }
        if (perUserLimit < 1) {
            throw CouponException.validation("Per-user usage limit must be at least 1");
        }
        if (totalLimit < 1) {
            throw CouponException.validation("Total usage limit must be at least 1");
        }
    }
}
