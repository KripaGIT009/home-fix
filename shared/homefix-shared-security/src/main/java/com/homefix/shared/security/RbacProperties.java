package com.homefix.shared.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Configuration properties for endpoint-to-role mappings used by
 * {@link RbacEnforcementFilter}.
 *
 * <p>Example {@code application.yml} snippet:
 * <pre>
 * homefix:
 *   security:
 *     rbac:
 *       endpoint-roles:
 *         "GET /admin/**": ["ADMIN", "SUPER_ADMIN"]
 *         "POST /bookings": ["CUSTOMER"]
 *         "PUT /providers/**": ["SERVICE_PROVIDER"]
 * </pre>
 *
 * <p>Each key is a pattern in the form {@code "METHOD /path/**"} where the path
 * may contain Ant-style wildcards.  The value is the list of roles <em>any one of
 * which</em> is sufficient to grant access (logical OR).
 */
@ConfigurationProperties(prefix = "homefix.security.rbac")
public class RbacProperties {

    /**
     * Map of {@code "METHOD /path"} → required roles.
     * Evaluated in insertion order; first matching entry wins.
     */
    private Map<String, List<String>> endpointRoles = new LinkedHashMap<>();

    public Map<String, List<String>> getEndpointRoles() {
        return endpointRoles;
    }

    public void setEndpointRoles(Map<String, List<String>> endpointRoles) {
        this.endpointRoles = endpointRoles;
    }
}
