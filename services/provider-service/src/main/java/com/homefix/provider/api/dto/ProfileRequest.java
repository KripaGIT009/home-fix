package com.homefix.provider.api.dto;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code PUT /providers/{id}/profile} (Requirement 4.1–4.3).
 *
 * <p>Bean-validation covers structural bounds (collection sizes, ranges). Deactivated-category
 * checks and cross-field rules are enforced in the service layer.
 */
public record ProfileRequest(
        @Size(max = 100, message = "display name must be at most 100 characters")
        String displayName,

        @Valid
        @NotNull(message = "categories is required")
        @Size(max = 5, message = "at most 5 active service categories are allowed")
        List<CategorySelection> categories,

        @NotNull(message = "skillTags is required")
        @Size(min = 1, max = 20, message = "skill tags must number between 1 and 20")
        List<String> skillTags,

        int yearsExperience,

        int serviceRadiusKm) {

    public record CategorySelection(
            @NotNull(message = "categoryId is required")
            UUID categoryId,

            @Size(max = 10, message = "at most 10 subcategories per category are allowed")
            List<UUID> subcategoryIds) {
    }
}
