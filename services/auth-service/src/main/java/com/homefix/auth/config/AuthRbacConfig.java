package com.homefix.auth.config;

import java.util.List;

import org.springframework.context.annotation.Configuration;

import com.homefix.shared.security.RbacProperties;

import jakarta.annotation.PostConstruct;

/**
 * Populates the shared {@link RbacProperties} allow-list for the Auth Service (Requirement 19.6).
 *
 * <p>The shared {@code RbacEnforcementFilter} already runs inside this service's security chain
 * (see {@link WebSecurityConfig}), but until the Admin Portal's User Management endpoints existed
 * there was nothing role-gated for it to protect. Without a rule, {@code /admin/users/**} would be
 * open to any authenticated caller, customers included. Rules are registered in
 * {@code @PostConstruct}, during context refresh and so before the server accepts traffic, for the
 * same reason as the other services' {@code *RbacConfig} classes.
 *
 * <ul>
 *   <li><strong>User Management</strong> ({@code /admin/users/**}, which also matches
 *       {@code /admin/users} itself) is ADMIN / SUPER_ADMIN. {@code AdminUserService} further
 *       limits status changes on administrator accounts to SUPER_ADMIN.</li>
 * </ul>
 *
 * <p>TENANT_ADMIN is not admitted to User Management: Tenant administrators never reach platform
 * administration endpoints (Requirement MT-10.3).
 *
 * <p>Deliberately no rule for the public {@code /auth/**} surface (registration, sign-in, refresh,
 * logout, introspection) or the health / metrics surface: the filter runs even for
 * {@code permitAll()} paths, and a rule there would turn every unauthenticated call into a 401.
 * Nor for {@code /internal/**}, whose callers are services holding the shared API key and the
 * {@code ROLE_INTERNAL} authority, not users with platform roles.
 */
@Configuration
public class AuthRbacConfig {

    private static final List<String> ADMINS = List.of("ADMIN", "SUPER_ADMIN");

    private final RbacProperties rbacProperties;

    public AuthRbacConfig(RbacProperties rbacProperties) {
        this.rbacProperties = rbacProperties;
    }

    @PostConstruct
    public void registerEndpointRoles() {
        var rules = rbacProperties.getEndpointRoles();
        // "/admin/users/**" also matches "/admin/users" itself (the list).
        rules.put("GET /admin/users/**", ADMINS);
        rules.put("PATCH /admin/users/**", ADMINS);
    }
}
