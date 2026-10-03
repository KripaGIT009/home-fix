package com.homefix.provider.api.dto;

import com.homefix.provider.service.TenantViews.MembershipView;

/**
 * Body of {@code GET /internal/tenants/{tenantId}/providers/{providerId}}: booking-service's
 * assignment check (Requirement MT-5.2, Property MT4). {@code assignable} is member, verification
 * {@code APPROVED} and not under review; it is false whenever verification could not be asked.
 */
public record TenantMembershipResponse(boolean member, boolean assignable, String verificationStatus) {

    public static TenantMembershipResponse from(MembershipView view) {
        return new TenantMembershipResponse(view.member(), view.assignable(), view.verificationStatus());
    }
}
