package com.homefix.catalog.blocker;

import java.util.List;

/**
 * Result of a category deletion blocker check (Requirement 3.6): the counts and identifiers of
 * active providers and active bookings that prevent deleting a category. When both lists are
 * empty the category may be deleted.
 *
 * @param providerIds     identifiers of active providers referencing the category
 * @param bookingReferences references of active bookings referencing the category
 */
public record CategoryBlockers(List<String> providerIds, List<String> bookingReferences) {

    public CategoryBlockers {
        providerIds = providerIds == null ? List.of() : List.copyOf(providerIds);
        bookingReferences = bookingReferences == null ? List.of() : List.copyOf(bookingReferences);
    }

    public static CategoryBlockers none() {
        return new CategoryBlockers(List.of(), List.of());
    }

    public int providerCount() {
        return providerIds.size();
    }

    public int bookingCount() {
        return bookingReferences.size();
    }

    /** @return {@code true} if any active provider or active booking blocks deletion. */
    public boolean hasBlockers() {
        return !providerIds.isEmpty() || !bookingReferences.isEmpty();
    }
}
