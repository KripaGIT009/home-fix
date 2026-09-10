package com.homefix.catalog.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Admin create/update payload for a {@link com.homefix.catalog.domain.ServiceCategory}
 * (Requirement 3.2).
 */
public record CategoryRequest(
        @NotBlank @Size(max = 120) String name,
        @Size(max = 1000) String description,
        @Size(max = 512) String iconUrl,
        int displayOrder) {
}
