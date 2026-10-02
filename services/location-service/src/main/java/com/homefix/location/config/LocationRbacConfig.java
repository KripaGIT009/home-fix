package com.homefix.location.config;

import java.util.List;

import org.springframework.context.annotation.Configuration;

import com.homefix.shared.security.RbacProperties;

import jakarta.annotation.PostConstruct;

/**
 * Populates the shared {@link RbacProperties} allow-list for the Location Service (Requirement
 * 19.6). Without rules the shared {@code RbacEnforcementFilter} passes every authenticated request
 * through, so any signed-in user could post positions for any booking.
 *
 * <ul>
 *   <li><strong>Posting a position</strong> is for SERVICE_PROVIDER only, and the controller
 *       records the token's subject as the provider rather than trusting the request body.</li>
 *   <li><strong>Reading</strong> the snapshot or the stream is for the customer tracking the job,
 *       the provider, and the staff roles that handle disputes and dispatch.</li>
 * </ul>
 *
 * <p>No rule covers the health / metrics surface: the filter runs inside the security chain even
 * for {@code permitAll()} paths, and a rule there would fail every unauthenticated probe.
 */
@Configuration
public class LocationRbacConfig {

    static final List<String> PROVIDER_ONLY = List.of("SERVICE_PROVIDER");

    static final List<String> READERS = List.of(
            "CUSTOMER", "SERVICE_PROVIDER", "ADMIN", "SUPER_ADMIN", "SUPPORT_AGENT", "DISPATCHER");

    private final RbacProperties rbacProperties;

    public LocationRbacConfig(RbacProperties rbacProperties) {
        this.rbacProperties = rbacProperties;
    }

    @PostConstruct
    public void registerEndpointRoles() {
        var rules = rbacProperties.getEndpointRoles();
        rules.put("POST /locations/**", PROVIDER_ONLY);
        rules.put("GET /locations/**", READERS);
    }
}
