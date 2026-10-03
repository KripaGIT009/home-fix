package com.homefix.provider.auth;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Port to the Auth Service's internal user surface, used by Tenant administration
 * (Requirements MT-2.2, MT-2.4, MT-3.2): finding an account by mobile number and granting or
 * revoking the {@code TENANT_ADMIN} role.
 *
 * <p>The Auth Service owns accounts and roles; the Provider Service only records which Tenant a
 * user administers or works for. Cross-service access is by API call only.
 */
public interface AuthUserClientPort {

    /** The role a Platform_Admin grants through Tenant administration. */
    String TENANT_ADMIN = "TENANT_ADMIN";

    /** The role an account needs to join a Tenant's team (Requirement MT-3.2). */
    String SERVICE_PROVIDER = "SERVICE_PROVIDER";

    /**
     * The account registered with {@code mobileNumber}.
     *
     * @return the account, or empty when no account has that number
     * @throws com.homefix.provider.service.ProviderException 503 {@code AUTH_UNAVAILABLE} when the
     *         Auth Service cannot answer — "unknown" must never be mistaken for "no such user"
     */
    Optional<AuthUser> findByMobile(String mobileNumber);

    /**
     * Grants {@code TENANT_ADMIN} (idempotent on the Auth Service side).
     *
     * @throws com.homefix.provider.service.ProviderException 404 {@code USER_NOT_FOUND} when the
     *         account no longer exists, 503 {@code AUTH_UNAVAILABLE} when the grant could not be made
     */
    void grantTenantAdmin(UUID userId);

    /**
     * Revokes {@code TENANT_ADMIN} (idempotent; the Auth Service also ends the user's sessions). An
     * account that no longer exists counts as revoked.
     *
     * @throws com.homefix.provider.service.ProviderException 503 {@code AUTH_UNAVAILABLE} when the
     *         revocation could not be made
     */
    void revokeTenantAdmin(UUID userId);

    /**
     * The account's mobile number, for display. Fails soft: empty when the account has none, does
     * not exist, or the Auth Service cannot answer.
     */
    Optional<String> mobileNumberOf(UUID userId);

    /** An account as the Auth Service's {@code by-mobile} lookup describes it. */
    record AuthUser(UUID userId, Set<String> roles, String status) {

        public AuthUser {
            roles = roles == null ? Set.of() : Set.copyOf(roles);
        }

        public boolean hasRole(String role) {
            return roles.contains(role);
        }
    }
}
