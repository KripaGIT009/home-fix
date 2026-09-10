package com.homefix.provider.service;

import java.util.List;
import java.util.UUID;

/**
 * Validated intent to replace a provider's profile selections (Requirement 4.1–4.3, 4.8).
 */
public record ProfileUpdateCommand(
        String displayName,
        List<CategorySelectionCommand> categories,
        List<String> skillTags,
        int yearsExperience,
        int serviceRadiusKm) {

    /** One category selection with its subcategories. */
    public record CategorySelectionCommand(UUID categoryId, List<UUID> subcategoryIds) {
    }
}
