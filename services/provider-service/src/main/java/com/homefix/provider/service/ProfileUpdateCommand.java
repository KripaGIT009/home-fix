package com.homefix.provider.service;

import java.util.List;
import java.util.UUID;

/**
 * Validated intent to replace a provider's profile selections (Requirement 4.1–4.3, 4.8).
 *
 * <p>{@code baseLatitude}/{@code baseLongitude} are the provider's base service location, the
 * point dispatch measures the service radius from (Requirement 8.2). They travel as a pair: both
 * present sets the location, both {@code null} leaves whatever is on file unchanged, and one
 * without the other is rejected.
 */
public record ProfileUpdateCommand(
        String displayName,
        List<CategorySelectionCommand> categories,
        List<String> skillTags,
        int yearsExperience,
        int serviceRadiusKm,
        Double baseLatitude,
        Double baseLongitude) {

    /** A profile update that does not touch the base service location. */
    public ProfileUpdateCommand(String displayName, List<CategorySelectionCommand> categories,
                                List<String> skillTags, int yearsExperience, int serviceRadiusKm) {
        this(displayName, categories, skillTags, yearsExperience, serviceRadiusKm, null, null);
    }

    /** One category selection with its subcategories. */
    public record CategorySelectionCommand(UUID categoryId, List<UUID> subcategoryIds) {
    }
}
