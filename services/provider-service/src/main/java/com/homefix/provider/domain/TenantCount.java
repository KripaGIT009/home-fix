package com.homefix.provider.domain;

import java.util.UUID;

/**
 * A per-Tenant row count (providers or administrators) for the Platform_Admin Tenant list
 * (Requirement MT-1.5), read with one grouped statement instead of one count per Tenant.
 */
public record TenantCount(UUID tenantId, long count) {
}
