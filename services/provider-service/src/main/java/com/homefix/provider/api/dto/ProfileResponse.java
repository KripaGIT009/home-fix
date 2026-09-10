package com.homefix.provider.api.dto;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.util.List;
import java.util.UUID;

import com.homefix.provider.domain.AvailabilitySlot;
import com.homefix.provider.domain.ProviderCategorySelection;
import com.homefix.provider.domain.ProviderProfile;

/**
 * Read model for a provider profile. Never exposes the encrypted bank account ciphertext;
 * only whether a verified account is on file.
 */
public record ProfileResponse(
        UUID id,
        String displayName,
        int yearsExperience,
        int serviceRadiusKm,
        BigDecimal aggregateRating,
        BigDecimal walletBalance,
        boolean emergencyAvailable,
        boolean underReview,
        boolean bankAccountVerified,
        List<String> skillTags,
        List<CategorySelection> categories,
        List<Slot> availability) {

    public record CategorySelection(UUID categoryId, List<UUID> subcategoryIds) {
    }

    public record Slot(DayOfWeek dayOfWeek, int startHour, int endHour) {
    }

    public static ProfileResponse from(ProviderProfile p) {
        List<CategorySelection> cats = p.getCategorySelections().stream()
                .map(ProfileResponse::toCategory)
                .toList();
        List<Slot> slots = p.getAvailabilitySlots().stream()
                .map(ProfileResponse::toSlot)
                .toList();
        return new ProfileResponse(
                p.getId(),
                p.getDisplayName(),
                p.getYearsExperience(),
                p.getServiceRadiusKm(),
                p.getAggregateRating(),
                p.getWalletBalance(),
                p.isEmergencyAvailable(),
                p.isUnderReview(),
                p.isBankAccountVerified(),
                List.copyOf(p.getSkillTags()),
                cats,
                slots);
    }

    private static CategorySelection toCategory(ProviderCategorySelection s) {
        return new CategorySelection(s.getCategoryId(), List.copyOf(s.getSubcategoryIds()));
    }

    private static Slot toSlot(AvailabilitySlot s) {
        return new Slot(s.getDayOfWeek(), s.getStartHour(), s.getEndHour());
    }
}
