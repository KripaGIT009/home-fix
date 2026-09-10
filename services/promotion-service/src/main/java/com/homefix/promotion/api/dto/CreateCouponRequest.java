package com.homefix.promotion.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.homefix.promotion.domain.DiscountType;
import com.homefix.promotion.service.CreateCouponCommand;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * Admin request to create a coupon (Requirement 21.1). Bean-validation annotations give an early
 * 400 for structurally invalid input; the deeper cross-field rules (expiry after valid_from, cap
 * required for PERCENTAGE) are enforced by the domain layer.
 */
public record CreateCouponRequest(
        @NotBlank
        @Pattern(regexp = "^[A-Za-z0-9]{4,20}$",
                message = "must be 4-20 alphanumeric characters")
        String code,

        @NotNull
        DiscountType discountType,

        @NotNull
        @Positive
        BigDecimal discountValue,

        @NotNull
        @PositiveOrZero
        BigDecimal minOrderValue,

        BigDecimal maxDiscountCap,

        @NotNull
        LocalDate validFrom,

        @NotNull
        LocalDate expiryDate,

        @Min(1)
        int perUserLimit,

        @Min(1)
        int totalLimit) {

    public CreateCouponCommand toCommand() {
        return new CreateCouponCommand(code, discountType, discountValue, minOrderValue,
                maxDiscountCap, validFrom, expiryDate, perUserLimit, totalLimit);
    }
}
