package com.homefix.catalog.api.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import com.homefix.catalog.domain.ServiceSubcategory;

/**
 * Customer-facing subcategory projection returned in catalog listings and cached
 * (Requirement 3.8). Only active subcategories of active categories are exposed.
 */
public record SubcategoryView(
        UUID id,
        UUID categoryId,
        String name,
        BigDecimal basePrice,
        int estimatedDurationMin,
        List<String> skillTags,
        boolean emergencyAvailable) {

    public static SubcategoryView from(ServiceSubcategory s) {
        return new SubcategoryView(
                s.getId(),
                s.getCategoryId(),
                s.getName(),
                s.getBasePrice(),
                s.getEstimatedDurationMin(),
                List.copyOf(s.getSkillTags()),
                s.isEmergencyAvailable());
    }
}
