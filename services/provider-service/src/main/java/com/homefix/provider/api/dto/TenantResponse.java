package com.homefix.provider.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.homefix.provider.domain.Tenant;
import com.homefix.provider.service.TenantViews.TenantView;

/**
 * A Tenant as the Admin Portal and the Tenant Portal read it ({@code Tenant} in the design,
 * Requirements MT-1.1, MT-1.5).
 */
public record TenantResponse(
        UUID id,
        String name,
        String status,
        String contactPhone,
        String contactEmail,
        double baseLatitude,
        double baseLongitude,
        BigDecimal serviceRadiusKm,
        List<UUID> categoryIds,
        long providerCount,
        long adminCount,
        Instant createdAt,
        Instant updatedAt,
        UUID applicantUserId,
        String rejectionReason) {

    public static TenantResponse from(TenantView view) {
        Tenant t = view.tenant();
        return new TenantResponse(t.getId(), t.getName(), t.getStatus().name(), t.getContactPhone(),
                t.getContactEmail(), t.getBaseLatitude(), t.getBaseLongitude(), t.getServiceRadiusKm(),
                t.getCategoryIds().stream().sorted().toList(), view.providerCount(), view.adminCount(),
                t.getCreatedAt(), t.getUpdatedAt(), t.getApplicantUserId(), t.getRejectionReason());
    }
}
