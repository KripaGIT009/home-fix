package com.homefix.notification.config;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

import com.homefix.shared.security.RbacProperties;

import jakarta.annotation.PostConstruct;

/**
 * Populates the shared {@link RbacProperties} allow-list for the Notification Service
 * (Requirement 19.6).
 *
 * <p>The service's only HTTP surface is the Admin Portal's Notification Templates module
 * ({@code /admin/notification-templates/**}, which also matches the bare list path), open to
 * ADMIN and SUPER_ADMIN. Without a rule the shared {@code RbacEnforcementFilter} passes an
 * unmatched request through, so any authenticated user — a customer included — could rewrite
 * the text sent to every customer. Rules are registered in {@code @PostConstruct}, during context
 * refresh and so before the server accepts traffic, as in the other services.
 *
 * <p>No rule covers the health / metrics surface: the filter runs inside the security chain even
 * for {@code permitAll()} paths, and a rule there would fail every unauthenticated probe. The
 * service exposes no {@code /internal/**} endpoints (it is a client of the Auth Service's).
 *
 * <p>Active under the same condition as the shared security auto-configuration that provides
 * {@link RbacProperties}; the Kafka integration test runs with {@code homefix.security.enabled=false}
 * and has neither.
 */
@Configuration
@ConditionalOnProperty(prefix = "homefix.security", name = "enabled", havingValue = "true", matchIfMissing = true)
public class NotificationRbacConfig {

    static final List<String> ADMIN_TIER = List.of("ADMIN", "SUPER_ADMIN");

    private final RbacProperties rbacProperties;

    public NotificationRbacConfig(RbacProperties rbacProperties) {
        this.rbacProperties = rbacProperties;
    }

    @PostConstruct
    public void registerEndpointRoles() {
        var rules = rbacProperties.getEndpointRoles();
        rules.put("GET /admin/notification-templates/**", ADMIN_TIER);
        rules.put("PUT /admin/notification-templates/**", ADMIN_TIER);
    }
}
