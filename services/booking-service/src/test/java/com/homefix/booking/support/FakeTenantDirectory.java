package com.homefix.booking.support;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.homefix.booking.tenant.TenantDirectoryPort;
import com.homefix.booking.tenant.TenantDirectoryUnavailableException;

/**
 * Scriptable {@link TenantDirectoryPort} for tests: Tenants covering every point, admins, members
 * and Provider-to-Tenant links are registered per test, and {@link #down} makes every call fail
 * like a Provider Service outage.
 */
public class FakeTenantDirectory implements TenantDirectoryPort {

    public final List<CoveringTenant> covering = new ArrayList<>();
    public final Map<UUID, TenantSummary> tenants = new HashMap<>();
    public final Map<UUID, UUID> adminToTenant = new HashMap<>();
    public final Map<String, TenantMembership> memberships = new HashMap<>();
    public final Map<UUID, UUID> providerToTenant = new HashMap<>();
    public final AtomicInteger byAdminCalls = new AtomicInteger();
    public volatile boolean down;
    /** Runs inside {@link #membership} before it answers, so a test can interleave callers. */
    public volatile Runnable onMembership = () -> { };

    public TenantSummary addTenant(String name, String status) {
        TenantSummary tenant = new TenantSummary(UUID.randomUUID(), name, status);
        tenants.put(tenant.tenantId(), tenant);
        return tenant;
    }

    public void addMember(UUID tenantId, UUID providerId, boolean assignable) {
        memberships.put(tenantId + "/" + providerId,
                new TenantMembership(true, assignable, assignable ? "APPROVED" : "PENDING"));
        providerToTenant.put(providerId, tenantId);
    }

    public void reset() {
        covering.clear();
        tenants.clear();
        adminToTenant.clear();
        memberships.clear();
        providerToTenant.clear();
        byAdminCalls.set(0);
        down = false;
        onMembership = () -> { };
    }

    @Override
    public List<CoveringTenant> covering(double latitude, double longitude, UUID categoryId) {
        failIfDown();
        return List.copyOf(covering);
    }

    @Override
    public Optional<TenantSummary> byAdmin(UUID userId) {
        byAdminCalls.incrementAndGet();
        failIfDown();
        return Optional.ofNullable(adminToTenant.get(userId)).map(tenants::get);
    }

    @Override
    public Optional<TenantMembership> membership(UUID tenantId, UUID providerId) {
        failIfDown();
        onMembership.run();
        return Optional.ofNullable(memberships.get(tenantId + "/" + providerId));
    }

    @Override
    public Optional<TenantRef> ofProvider(UUID providerId) {
        failIfDown();
        return Optional.ofNullable(providerToTenant.get(providerId))
                .map(id -> new TenantRef(id, tenants.containsKey(id) ? tenants.get(id).name() : null));
    }

    @Override
    public Optional<TenantSummary> byId(UUID tenantId) {
        failIfDown();
        return Optional.ofNullable(tenants.get(tenantId));
    }

    private void failIfDown() {
        if (down) {
            throw new TenantDirectoryUnavailableException("provider-service down", new RuntimeException("down"));
        }
    }
}
