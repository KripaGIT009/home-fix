package com.homefix.catalog.api.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import com.homefix.catalog.domain.ServiceSubcategory;

/**
 * Admin-facing subcategory response including the active flag (Requirement 3.3, 3.7).
 */
public record SubcategoryResponse(
        UUID id,
        UUID categoryId,
        String name,
        BigDecimal basePrice,
        int estimatedDurationMin,
        List<String> skillTags,
        boolean emergencyAvailable,
        boolean active) {

    public static SubcategoryResponse from(ServiceSubcategory s) {
        return new SubcategoryResponse(
                s.getId(),
                s.getCategoryId(),
                s.getName(),
                s.getBasePrice(),
                s.getEstimatedDurationMin(),
                List.copyOf(s.getSkillTags()),
                s.isEmergencyAvailable(),
                s.isActive());
    }
}
