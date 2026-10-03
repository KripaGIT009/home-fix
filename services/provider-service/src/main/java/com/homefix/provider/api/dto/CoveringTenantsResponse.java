package com.homefix.provider.api.dto;

import java.util.List;
import java.util.UUID;

import com.homefix.provider.service.TenantViews.CoveringTenant;

/**
 * Body of {@code GET /internal/tenants/covering}: the {@code ACTIVE} Tenants covering a booking,
 * nearest first (Requirement MT-4.1). An empty list is a normal answer.
 */
public record CoveringTenantsResponse(List<Item> tenants) {

    public static CoveringTenantsResponse from(List<CoveringTenant> covering) {
        return new CoveringTenantsResponse(covering.stream()
                .map(c -> new Item(c.tenantId(), c.name(), c.distanceKm()))
                .toList());
    }

    public record Item(UUID tenantId, String name, double distanceKm) {
    }
}
