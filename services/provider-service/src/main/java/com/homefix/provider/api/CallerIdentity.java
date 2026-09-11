package com.homefix.provider.api;

import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import com.homefix.provider.service.ProviderException;

/**
 * Resolves the authenticated caller and asserts per-resource ownership.
 *
 * <p>The shared {@code RbacEnforcementFilter} can only answer "does this caller hold an acceptable
 * <em>role</em>?". Every provider endpoint is addressed by a {@code {id}} provider identifier, so a
 * role check alone would let any principal holding {@code ROLE_SERVICE_PROVIDER} read and mutate
 * <em>another</em> provider's profile, availability, earnings, and settlements. This component
 * supplies the missing dimension: the caller must be the targeted provider themselves, or hold a
 * staff role that legitimately acts on behalf of others.
 */
@Component
public class CallerIdentity {

    /** Roles that may legitimately act on another user's resource. */
    private static final Set<String> STAFF_AUTHORITIES = Set.of(
            "ROLE_ADMIN", "ROLE_SUPER_ADMIN", "ROLE_FINANCE_ADMIN",
            "ROLE_SUPPORT_AGENT", "ROLE_DISPATCHER");

    /**
     * @return the authenticated caller's user id (the JWT subject).
     * @throws ProviderException 401 when unauthenticated or the principal name is not a UUID
     */
    public UUID requireCallerId() {
        Authentication authentication = requireAuthentication();
        try {
            return UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException e) {
            throw new ProviderException(HttpStatus.UNAUTHORIZED, "INVALID_PRINCIPAL",
                    "authenticated principal is not a valid user ID");
        }
    }

    /** @return true when the caller holds a staff role and may act on behalf of others. */
    public boolean isStaff() {
        return requireAuthentication().getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(STAFF_AUTHORITIES::contains);
    }

    /**
     * Asserts the caller is {@code target} themselves, or holds a staff role.
     *
     * @throws ProviderException 403 when an ordinary caller targets somebody else's resource,
     *                           401 when there is no authenticated principal
     */
    public void requireSelfOrStaff(UUID target) {
        if (isStaff()) {
            return;
        }
        if (!requireCallerId().equals(target)) {
            throw new ProviderException(HttpStatus.FORBIDDEN, "FORBIDDEN",
                    "caller may only act on their own provider record");
        }
    }

    private Authentication requireAuthentication() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ProviderException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
                    "no authenticated principal");
        }
        return authentication;
    }
}
