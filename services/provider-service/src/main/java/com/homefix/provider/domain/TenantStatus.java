package com.homefix.provider.domain;

/**
 * Lifecycle of a {@link Tenant} (Requirement MT-1.1). A {@code SUSPENDED} Tenant is excluded from
 * coverage and its administrators are refused every Tenant Portal action, while its Providers stay
 * eligible for automatic matching (Requirement MT-1.4).
 *
 * <p>An agency that registers itself starts {@code PENDING_APPROVAL} and becomes {@code ACTIVE} or
 * {@code REJECTED} by a Platform_Admin's decision (email-auth Requirement 5). Only {@code ACTIVE}
 * covers bookings.
 */
public enum TenantStatus {
    ACTIVE,
    SUSPENDED,
    PENDING_APPROVAL,
    REJECTED;

    /** Whether a Platform_Admin may set this status directly (edit), rather than by a decision. */
    public boolean isEditable() {
        return this == ACTIVE || this == SUSPENDED;
    }
}
