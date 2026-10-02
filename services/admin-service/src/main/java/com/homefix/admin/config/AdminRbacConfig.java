package com.homefix.admin.config;

import java.util.List;

import org.springframework.context.annotation.Configuration;

import com.homefix.shared.security.RbacProperties;

import jakarta.annotation.PostConstruct;

/**
 * Populates the shared {@link RbacProperties} allow-list for the Admin Service (Requirement 19.6).
 *
 * <p>Spring's relaxed binder cannot bind map keys containing spaces and slashes (e.g.
 * {@code "GET /admin/**"}) from flat {@code application.yml} strings, so the rules are added
 * programmatically. They are registered in {@code @PostConstruct}, during context refresh and so
 * before the server accepts traffic: on {@code ApplicationReadyEvent} the filter would briefly see
 * an empty allow-list and, as documented for "no rule matches", pass every request through.
 *
 * <p>Rule ordering matters: {@code RbacEnforcementFilter} takes the first matching entry. The
 * System Configuration paths are declared <em>before</em> the catch-all {@code /admin/**} so they
 * are restricted to SUPER_ADMIN, giving the required 403 for an ADMIN principal (Requirement 19.7)
 * as a defence-in-depth layer alongside the per-module {@code AdminAuthorization} check. The Audit
 * Logs view is named explicitly (ADMIN, SUPER_ADMIN) so a later change to the catch-all cannot
 * silently widen it. Every other Admin endpoint is open to both ADMIN and SUPER_ADMIN; PATCH is
 * covered too, since an unruled method would be open to any authenticated user.
 */
@Configuration
public class AdminRbacConfig {

    private static final List<String> ADMIN_TIER = List.of("ADMIN", "SUPER_ADMIN");
    private static final List<String> SUPER_ADMIN_ONLY = List.of("SUPER_ADMIN");

    private final RbacProperties rbacProperties;

    public AdminRbacConfig(RbacProperties rbacProperties) {
        this.rbacProperties = rbacProperties;
    }

    @PostConstruct
    public void registerEndpointRoles() {
        var rules = rbacProperties.getEndpointRoles();
        // Most specific first: System Configuration is SUPER_ADMIN only.
        for (String method : List.of("GET", "PUT", "POST", "PATCH", "DELETE")) {
            rules.put(method + " /admin/system-config/**", SUPER_ADMIN_ONLY);
        }
        // Audit Logs: read-only, Admin tier.
        rules.put("GET /admin/audit-logs/**", ADMIN_TIER);
        // Catch-all Admin tier for every other module.
        for (String method : List.of("GET", "POST", "PUT", "PATCH", "DELETE")) {
            rules.put(method + " /admin/**", ADMIN_TIER);
        }
    }
}
