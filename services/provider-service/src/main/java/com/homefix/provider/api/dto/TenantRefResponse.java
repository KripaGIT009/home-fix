package com.homefix.provider.api.dto;

import java.util.UUID;

import com.homefix.provider.domain.Tenant;

/**
 * A Tenant's identity and status for booking-service: the body of
 * {@code GET /internal/tenants/by-admin/{userId}} and {@code GET /internal/tenants/{tenantId}}.
 */
public record TenantRefResponse(UUID tenantId, String name, String status) {

    public static TenantRefResponse from(Tenant tenant) {
        return new TenantRefResponse(tenant.getId(), tenant.getName(), tenant.getStatus().name());
    }
}
