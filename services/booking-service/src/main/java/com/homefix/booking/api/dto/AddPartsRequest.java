package com.homefix.booking.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request body for adding a parts/materials line item during job execution (Requirement 6.8,
 * 11.3): item name, quantity (minimum 1), and unit cost (minimum 0.01).
 */
public record AddPartsRequest(
        @NotBlank(message = "item name is required")
        @Size(max = 200, message = "item name must be at most 200 characters")
        String itemName,

        @Min(value = 1, message = "quantity must be at least 1")
        int quantity,

        @NotNull(message = "unit cost is required")
        @DecimalMin(value = "0.01", message = "unit cost must be at least 0.01")
        BigDecimal unitCost) {
}
