package com.homefix.admin.rbac;

import java.util.Set;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;

/**
 * Encapsulates the Admin module RBAC rule (Requirement 19.6, 19.7):
 *
 * <ul>
 *   <li>SUPER_ADMIN may access every module.</li>
 *   <li>ADMIN may access every module <em>except</em> System Configuration.</li>
 * </ul>
 *
 * <p>This complements the coarse-grained shared {@code RbacEnforcementFilter} (which gates the
 * whole {@code /admin/**} surface to the Admin tier). The finer "ADMIN cannot reach System
 * Configuration" rule is expressed here because the shared filter's allow-list is a logical OR
 * and cannot subtract a single module from ADMIN. Controllers call
 * {@link #requireAccess(Authentication, AdminModule)} at the top of each handler.
 */
@Component
public class AdminAuthorization {

    /** {@code true} if the principal's roles permit access to the given module. */
    public boolean canAccess(Set<String> roles, AdminModule module) {
        boolean superAdmin = roles.contains("SUPER_ADMIN");
        if (superAdmin) {
            return true;
        }
        boolean admin = roles.contains("ADMIN");
        if (!admin) {
            return false;
        }
        // ADMIN: everything except SUPER_ADMIN-only modules (System Configuration).
        return !module.isSuperAdminOnly();
    }

    /**
     * Enforces {@link #canAccess} against a Spring Security {@link Authentication}, throwing
     * {@link ModuleAccessDeniedException} (→ 403) when the module is not permitted.
     */
    public void requireAccess(Authentication authentication, AdminModule module) {
        Set<String> roles = extractRoles(authentication);
        if (!canAccess(roles, module)) {
            throw new ModuleAccessDeniedException(module);
        }
    }

    /**
     * Extracts bare role names from the authorities, stripping the Spring {@code ROLE_} prefix
     * applied by the shared {@code JwtValidationFilter}.
     */
    public Set<String> extractRoles(Authentication authentication) {
        if (authentication == null) {
            return Set.of();
        }
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .map(a -> a.startsWith("ROLE_") ? a.substring("ROLE_".length()) : a)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
