package com.homefix.admin.api;

import java.util.UUID;

import org.springframework.security.core.Authentication;

/**
 * Small helper for resolving the acting Admin's user ID from the security context. The shared
 * {@code JwtValidationFilter} sets the authentication name to the JWT subject, which is the user
 * UUID. Used to stamp the {@code actor_id} on every Audit_Log entry (Requirement 19.8).
 */
public final class AdminPrincipals {

    private AdminPrincipals() {
    }

    /**
     * Resolves the actor UUID from the authentication subject. Falls back to a nil UUID only if
     * the subject is absent or unparseable, which cannot happen for an authenticated Admin request
     * that reached a controller (the RBAC filter rejects unauthenticated calls upstream).
     */
    public static UUID actorId(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            return new UUID(0L, 0L);
        }
        try {
            return UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException ex) {
            return new UUID(0L, 0L);
        }
    }
}
