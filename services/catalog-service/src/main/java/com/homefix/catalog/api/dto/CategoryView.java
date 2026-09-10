package com.homefix.catalog.api.dto;

import java.util.List;
import java.util.UUID;

import com.homefix.catalog.domain.ServiceCategory;

/**
 * Customer-facing category projection with its active subcategories, returned in catalog
 * listings and cached (Requirement 3.8). Only active categories are exposed to customers
 * (Requirement 3.5).
 */
public record CategoryView(
        UUID id,
        String name,
        String description,
        String iconUrl,
        int displayOrder,
        List<SubcategoryView> subcategories) {

    public static CategoryView from(ServiceCategory category, List<SubcategoryView> subcategories) {
        return new CategoryView(
                category.getId(),
                category.getName(),
                category.getDescription(),
                category.getIconUrl(),
                category.getDisplayOrder(),
                subcategories == null ? List.of() : List.copyOf(subcategories));
    }
}
