package com.homefix.provider.api.dto;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code PUT /providers/{id}/profile} (Requirement 4.1–4.3).
 *
 * <p>Bean-validation covers structural bounds (collection sizes, ranges). Deactivated-category
 * checks and cross-field rules are enforced in the service layer.
 *
 * <p>{@code baseLatitude}/{@code baseLongitude} are optional and travel as a pair (the
 * both-or-neither rule is a cross-field check in the service). Omitting both leaves the base
 * service location on file unchanged, so a client that predates the field cannot wipe it — and
 * with it the provider's dispatch eligibility — by re-saving the profile.
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

        int serviceRadiusKm,

        @DecimalMin(value = "-90.0", message = "baseLatitude must be between -90 and 90")
        @DecimalMax(value = "90.0", message = "baseLatitude must be between -90 and 90")
        Double baseLatitude,

        @DecimalMin(value = "-180.0", message = "baseLongitude must be between -180 and 180")
        @DecimalMax(value = "180.0", message = "baseLongitude must be between -180 and 180")
        Double baseLongitude) {

    public record CategorySelection(
            @NotNull(message = "categoryId is required")
            UUID categoryId,

            @Size(max = 10, message = "at most 10 subcategories per category are allowed")
            List<UUID> subcategoryIds) {
    }
}
