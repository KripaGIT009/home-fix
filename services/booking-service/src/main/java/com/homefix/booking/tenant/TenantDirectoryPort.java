package com.homefix.booking.tenant;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The Provider Service's Tenant registry, as booking-service needs it (design D1, D2): which
 * Tenants cover a booking, which Tenant a Tenant_Admin runs, whether a Provider may be assigned by
 * a Tenant, which Tenant a Provider belongs to, and a Tenant's name.
 *
 * <p>Every method distinguishes an <em>answer</em> from an <em>outage</em>. "No Tenant" (the
 * Provider Service's 404, or an empty coverage list) is an answer and comes back empty; a failed
 * call — the service down, timing out, refusing the internal key — throws
 * {@link TenantDirectoryUnavailableException}, so each caller can choose its own degradation:
 * the fallback fails the booking (Requirement MT-4.4), the Tenant endpoints answer 503, and the
 * best-effort lookups (Tenant on automatic acceptance, Tenant name on a detail) carry on without
 * one (Requirement MT-8.2).
 */
public interface TenantDirectoryPort {

    /**
     * {@code GET /internal/tenants/covering?lat&lon&categoryId}: the {@code ACTIVE} Tenants whose
     * Service_Area contains the point and whose categories include {@code categoryId}, nearest
     * first (Requirement MT-4.1, MT-1.4).
     *
     * @throws TenantDirectoryUnavailableException when the lookup fails
     */
    List<CoveringTenant> covering(double latitude, double longitude, UUID categoryId);

    /**
     * {@code GET /internal/tenants/by-admin/{userId}}: the Tenant {@code userId} administers, or
     * empty when they administer none (Requirement MT-10.1).
     *
     * @throws TenantDirectoryUnavailableException when the lookup fails
     */
    Optional<TenantSummary> byAdmin(UUID userId);

    /**
     * {@code GET /internal/tenants/{tenantId}/providers/{providerId}}: the Provider's standing in
     * the Tenant, or empty when the Provider is not a member (Requirement MT-5.2).
     *
     * @throws TenantDirectoryUnavailableException when the lookup fails
     */
    Optional<TenantMembership> membership(UUID tenantId, UUID providerId);

    /**
     * {@code GET /internal/tenants/of-provider/{providerId}}: the Provider's Tenant, or empty for an
     * Independent_Provider (Requirement MT-8.1).
     *
     * @throws TenantDirectoryUnavailableException when the lookup fails
     */
    Optional<TenantRef> ofProvider(UUID providerId);

    /**
     * {@code GET /internal/tenants/{tenantId}}: the Tenant, or empty when unknown.
     *
     * @throws TenantDirectoryUnavailableException when the lookup fails
     */
    Optional<TenantSummary> byId(UUID tenantId);

    /** A Tenant covering a booking, with its distance from the service address. */
    record CoveringTenant(UUID tenantId, String name, double distanceKm) {
    }

    /** A Tenant's identity and status ({@code ACTIVE} or {@code SUSPENDED}). */
    record TenantSummary(UUID tenantId, String name, String status) {

        /** Requirement MT-1.4: a suspended Tenant's admins are refused every action. */
        public boolean suspended() {
            return "SUSPENDED".equalsIgnoreCase(status);
        }
    }

    /**
     * A Provider's standing in a Tenant. {@code assignable} is the Provider Service's
     * Assignable_Provider rule: a member, {@code APPROVED}, not under review (Requirement MT-5.2).
     */
    record TenantMembership(boolean member, boolean assignable, String verificationStatus) {
    }

    /** The Tenant a Provider belongs to. */
    record TenantRef(UUID tenantId, String name) {
    }
}
