package com.homefix.dispatch.config;

import java.util.List;

import org.springframework.context.annotation.Configuration;

import com.homefix.shared.security.RbacProperties;

import jakarta.annotation.PostConstruct;

/**
 * Populates the shared {@link RbacProperties} allow-list for the Dispatch Engine (Requirement 19.6).
 *
 * <p>Without rules the shared {@code RbacEnforcementFilter} finds no matching pattern and passes
 * every request through, leaving each endpoint gated only by "is this token valid". Rules are
 * registered in {@code @PostConstruct}, during context refresh and so before the server accepts
 * traffic, for the same reason as the other services' {@code *RbacConfig} classes.
 *
 * <ul>
 *   <li><strong>Job offers</strong> ({@code /dispatch/offers/**}) are for SERVICE_PROVIDER only.
 *       The role gate is coarse; {@code JobOfferController} additionally restricts each offer to
 *       the provider it was made to. Staff have no read path here: an offer is a short-lived
 *       prompt to one provider, not a record.</li>
 *   <li><strong>Matching weights</strong> ({@code /admin/dispatch/weights}) are System
 *       Configuration: SUPER_ADMIN may change them, ADMIN may also read them.</li>
 *   <li><strong>Dispatch rules</strong> ({@code /admin/dispatch/config}, the Admin Portal's weights
 *       + radius + timeout form) follow the same write rule (SUPER_ADMIN), but DISPATCHER may also
 *       read them: dispatchers work under these rules and the portal shows them read-only.</li>
 * </ul>
 *
 * <p>No rule covers the health / metrics surface: the filter runs inside the security chain even
 * for {@code permitAll()} paths, and a rule there would fail every unauthenticated probe.
 */
@Configuration
public class DispatchRbacConfig {

    private static final List<String> PROVIDER_ONLY = List.of("SERVICE_PROVIDER");

    private final RbacProperties rbacProperties;

    public DispatchRbacConfig(RbacProperties rbacProperties) {
        this.rbacProperties = rbacProperties;
    }

    @PostConstruct
    public void registerEndpointRoles() {
        var rules = rbacProperties.getEndpointRoles();
        // "/dispatch/offers/**" also matches "/dispatch/offers" itself (the pending-offer list).
        rules.put("GET /dispatch/offers/**", PROVIDER_ONLY);
        rules.put("POST /dispatch/offers/**", PROVIDER_ONLY);
        rules.put("PUT /admin/dispatch/**", List.of("SUPER_ADMIN"));
        // First match wins: the narrower config read rule must precede the /admin/dispatch/** one.
        rules.put("GET /admin/dispatch/config", List.of("ADMIN", "SUPER_ADMIN", "DISPATCHER"));
        rules.put("GET /admin/dispatch/**", List.of("ADMIN", "SUPER_ADMIN"));
    }
}
