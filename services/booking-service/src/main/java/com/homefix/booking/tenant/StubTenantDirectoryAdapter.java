package com.homefix.booking.tenant;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Dev/test {@link TenantDirectoryPort} that knows no Tenants, so every booking that automatic
 * matching cannot place fails exactly as before the Tenant feature (Requirement MT-4.3). Selected
 * with {@code homefix.booking.tenant-directory-client=stub}.
 */
@Component
@ConditionalOnProperty(name = "homefix.booking.tenant-directory-client", havingValue = "stub")
public class StubTenantDirectoryAdapter implements TenantDirectoryPort {

    @Override
    public List<CoveringTenant> covering(double latitude, double longitude, UUID categoryId) {
        return List.of();
    }

    @Override
    public Optional<TenantSummary> byAdmin(UUID userId) {
        return Optional.empty();
    }

    @Override
    public Optional<TenantMembership> membership(UUID tenantId, UUID providerId) {
        return Optional.empty();
    }

    @Override
    public Optional<TenantRef> ofProvider(UUID providerId) {
        return Optional.empty();
    }

    @Override
    public Optional<TenantSummary> byId(UUID tenantId) {
        return Optional.empty();
    }
}
