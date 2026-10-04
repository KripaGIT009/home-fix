package com.homefix.provider.api.dto;

import java.time.Instant;
import java.util.UUID;

import com.homefix.provider.domain.Tenant;

/**
 * An agency application as its applicant sees it (email-auth Requirement 5.5): pending, approved
 * (sign in again to open the Tenant Portal) or rejected with the reason.
 */
public record TenantApplicationResponse(UUID tenantId, String name, String status, String rejectionReason,
                                        Instant createdAt) {

    public static TenantApplicationResponse from(Tenant tenant) {
        return new TenantApplicationResponse(tenant.getId(), tenant.getName(), tenant.getStatus().name(),
                tenant.getRejectionReason(), tenant.getCreatedAt());
    }
}
