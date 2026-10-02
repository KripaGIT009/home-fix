package com.homefix.dispatch.port;

import java.util.List;
import java.util.UUID;

/**
 * Outbound port to the Service Catalog for the skill tags a subcategory requires (Requirements
 * 3.3, 8.2). A provider is eligible for a booking only when they carry at least one of these tags,
 * so they are part of the matching problem; {@code BookingCreated} carries only the
 * {@code subcategoryId}, and the Dispatch Engine resolves the tags itself before matching.
 */
public interface SubcategorySkillsPort {

    /**
     * Returns the skill tags configured on a subcategory.
     *
     * @param subcategoryId the booked subcategory
     * @return the subcategory's skill tags (never {@code null}; empty when the catalog has none
     *         configured for it)
     * @throws com.homefix.dispatch.domain.UnresolvableBookingException if the subcategory is not
     *         in the active catalog; retrying cannot help
     * @throws com.homefix.dispatch.domain.EnrichmentUnavailableException if the catalog could not
     *         be consulted; retrying may help
     */
    List<String> requiredSkillTags(UUID subcategoryId);
}
