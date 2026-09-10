package com.homefix.admin.config;

import java.util.List;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;

import com.homefix.shared.security.RbacProperties;

/**
 * Populates the shared {@link RbacProperties} allow-list for the Admin Service (Requirement 19.6).
 *
 * <p>Spring's relaxed binder cannot bind map keys containing spaces and slashes (e.g.
 * {@code "GET /admin/**"}) from flat {@code application.yml} strings, so the rules are added
 * programmatically here once the context is ready.
 *
 * <p>Rule ordering matters: {@code RbacEnforcementFilter} takes the first matching entry. The
 * System Configuration paths are declared <em>before</em> the catch-all {@code /admin/**} so they
 * are restricted to SUPER_ADMIN, giving the required 403 for an ADMIN principal (Requirement 19.7)
 * as a defence-in-depth layer alongside the per-module {@code AdminAuthorization} check. Every
 * other Admin endpoint is open to both ADMIN and SUPER_ADMIN.
 */
@Configuration
public class AdminRbacConfig {

    private static final List<String> ADMIN_TIER = List.of("ADMIN", "SUPER_ADMIN");
    private static final List<String> SUPER_ADMIN_ONLY = List.of("SUPER_ADMIN");

    private final RbacProperties rbacProperties;

    public AdminRbacConfig(RbacProperties rbacProperties) {
        this.rbacProperties = rbacProperties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void registerEndpointRoles() {
        var rules = rbacProperties.getEndpointRoles();
        // Most specific first: System Configuration is SUPER_ADMIN only.
        rules.put("GET /admin/system-config/**", SUPER_ADMIN_ONLY);
        rules.put("PUT /admin/system-config/**", SUPER_ADMIN_ONLY);
        rules.put("POST /admin/system-config/**", SUPER_ADMIN_ONLY);
        rules.put("DELETE /admin/system-config/**", SUPER_ADMIN_ONLY);
        // Catch-all Admin tier for every other module.
        rules.put("GET /admin/**", ADMIN_TIER);
        rules.put("POST /admin/**", ADMIN_TIER);
        rules.put("PUT /admin/**", ADMIN_TIER);
        rules.put("DELETE /admin/**", ADMIN_TIER);
    }
}
