package com.homefix.provider.api.dto;

import java.util.UUID;

import com.homefix.provider.domain.Tenant;

/** Body of {@code GET /internal/tenants/of-provider/{providerId}} (Requirement MT-8.1). */
public record ProviderTenantResponse(UUID tenantId, String name) {

    public static ProviderTenantResponse from(Tenant tenant) {
        return new ProviderTenantResponse(tenant.getId(), tenant.getName());
    }
}
