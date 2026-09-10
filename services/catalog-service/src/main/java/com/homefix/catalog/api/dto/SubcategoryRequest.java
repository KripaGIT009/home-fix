package com.homefix.catalog.api.dto;

import java.math.BigDecimal;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Admin create/update payload for a {@link com.homefix.catalog.domain.ServiceSubcategory}
 * (Requirement 3.3, 3.7). Range validation (base price 0.01–999,999.99, duration 1–480,
 * skill tag count) is enforced in the service layer against {@code CatalogProperties} so the
 * bounds are configurable, but obviously-invalid nulls are caught here.
 */
public record SubcategoryRequest(
        @NotBlank @Size(max = 120) String name,
        @NotNull BigDecimal basePrice,
        int estimatedDurationMin,
        List<String> skillTags,
        boolean emergencyAvailable) {
}
