package com.homefix.catalog.api.dto;

import java.util.UUID;

import com.homefix.catalog.domain.ServiceCategory;

/**
 * Admin-facing category response including the active flag (Requirement 3.1–3.2).
 */
public record CategoryResponse(
        UUID id,
        String name,
        String description,
        String iconUrl,
        int displayOrder,
        boolean active) {

    public static CategoryResponse from(ServiceCategory c) {
        return new CategoryResponse(
                c.getId(),
                c.getName(),
                c.getDescription(),
                c.getIconUrl(),
                c.getDisplayOrder(),
                c.isActive());
    }
}
