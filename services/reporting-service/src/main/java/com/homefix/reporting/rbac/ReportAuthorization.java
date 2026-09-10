package com.homefix.reporting.rbac;

import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;

import com.homefix.reporting.domain.ReportType;

/**
 * Encapsulates the Reporting-service RBAC rule (Requirement 20.6):
 *
 * <ul>
 *   <li>Finance-restricted reports (Payment Reconciliation, Settlement) require the
 *       {@code FINANCE_ADMIN} role.</li>
 *   <li>Every other report is available to any Admin-tier principal, gated coarsely by the shared
 *       {@code RbacEnforcementFilter} on the {@code /reports/**} surface.</li>
 * </ul>
 *
 * <p>The core decision ({@link #canAccess(Set, ReportType)}) is a pure function over a role set so
 * it can be unit-tested without a Spring context, mirroring the Admin service's
 * {@code AdminAuthorization}.
 */
@Component
public class ReportAuthorization {

    static final String FINANCE_ADMIN_ROLE = "FINANCE_ADMIN";

    /** {@code true} if the principal's roles permit generating the given report type. */
    public boolean canAccess(Set<String> roles, ReportType reportType) {
        if (!reportType.isFinanceRestricted()) {
            return true;
        }
        return roles.contains(FINANCE_ADMIN_ROLE);
    }

    /**
     * Enforces {@link #canAccess} against a Spring Security {@link Authentication}, throwing
     * {@link ReportAccessDeniedException} (&rarr; 403) when the report type is not permitted for
     * the principal's roles (Requirement 20.6).
     */
    public void requireAccess(Authentication authentication, ReportType reportType) {
        if (!canAccess(extractRoles(authentication), reportType)) {
            throw new ReportAccessDeniedException(reportType);
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
                .collect(Collectors.toUnmodifiableSet());
    }
}
