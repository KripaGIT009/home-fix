package com.homefix.provider.domain;

/**
 * Lifecycle of a {@link Tenant} (Requirement MT-1.1). A {@code SUSPENDED} Tenant is excluded from
 * coverage and its administrators are refused every Tenant Portal action, while its Providers stay
 * eligible for automatic matching (Requirement MT-1.4).
 */
public enum TenantStatus {
    ACTIVE,
    SUSPENDED
}
