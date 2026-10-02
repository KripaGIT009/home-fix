package com.homefix.provider.eligibility;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * One dispatch search: where the customer is, how far to look, whether it is an emergency, and
 * the skill tags the booked subcategory requires (Requirement 8.2).
 *
 * <p>{@code skillTags} is normalised on construction — trimmed, lower-cased, blanks and duplicates
 * dropped — so tag matching is case-insensitive and the skill score's denominator counts each
 * required tag once however the caller spelled the list.
 *
 * @param subcategoryId the booked subcategory; accepted for the contract and for logging, but not
 *                      an eligibility filter — Requirement 8.2 matches on skill tags, which the
 *                      Dispatch Engine has already resolved from this subcategory
 * @param latitude      customer latitude, decimal degrees
 * @param longitude     customer longitude, decimal degrees
 * @param radiusKm      search radius in km; each provider is further bounded by their own radius
 * @param emergency     {@code true} restricts the search to emergency-available providers
 * @param skillTags     required tags; a provider must carry at least one
 */
public record EligibilityQuery(
        UUID subcategoryId,
        double latitude,
        double longitude,
        double radiusKm,
        boolean emergency,
        List<String> skillTags) {

    public EligibilityQuery {
        Set<String> normalised = new LinkedHashSet<>();
        if (skillTags != null) {
            for (String tag : skillTags) {
                if (tag != null && !tag.isBlank()) {
                    normalised.add(normaliseTag(tag));
                }
            }
        }
        skillTags = List.copyOf(normalised);
    }

    /** The canonical form tags are compared in: trimmed and lower-cased (locale-independent). */
    public static String normaliseTag(String tag) {
        return tag.trim().toLowerCase(Locale.ROOT);
    }
}
