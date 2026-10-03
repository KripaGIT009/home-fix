package com.homefix.provider.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import com.homefix.provider.domain.Tenant;

/**
 * The read models the Tenant services hand to the web layer (Requirements MT-1 to MT-3, MT-8.4).
 * Kept as plain records so controllers map them to the API shapes without touching entities.
 */
public final class TenantViews {

    private TenantViews() {
    }

    /** A Tenant with its team and administrator counts (Requirement MT-1.5). */
    public record TenantView(Tenant tenant, long providerCount, long adminCount) {
    }

    /** One administrator of a Tenant. {@code mobileNumber} is null when it could not be resolved. */
    public record AdminView(UUID userId, String mobileNumber) {
    }

    /**
     * One Provider of a Tenant's team (Requirements MT-3.4, MT-8.4).
     *
     * @param mobileNumber       null in lists — the number lives in the Auth Service and one lookup
     *                           per member is not worth it; set when the Provider was just added
     *                           by that number
     * @param verificationStatus the raw verification status, or null when the provider has no
     *                           record or the Verification Service could not be asked
     * @param availableNow       inside an availability slot now, by the dispatch rule
     * @param assignable         member, {@code APPROVED} and not under review (Requirement MT-5.2)
     */
    public record TeamProviderView(UUID providerId, String displayName, String mobileNumber,
                                   String primarySkill, String verificationStatus, BigDecimal rating,
                                   boolean availableNow, boolean assignable) {
    }

    /** Everyone attached to a Tenant, for the Platform_Admin members screen (Requirement MT-12.3). */
    public record MembersView(List<AdminView> admins, List<TeamProviderView> providers) {
    }

    /** A Tenant covering a location, with the distance from its base (Requirement MT-4.1). */
    public record CoveringTenant(UUID tenantId, String name, double distanceKm) {
    }

    /** A team member's standing for booking-service's assignment check (Requirement MT-5.2). */
    public record MembershipView(boolean member, boolean assignable, String verificationStatus) {
    }
}
