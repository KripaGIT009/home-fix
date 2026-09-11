package com.homefix.customer.config;

import java.util.List;

import org.springframework.context.annotation.Configuration;

import com.homefix.shared.security.RbacProperties;

import jakarta.annotation.PostConstruct;

/**
 * Populates the shared {@link RbacProperties} allow-list for the Customer Service
 * (Requirement 2, 19.6).
 *
 * <p>Spring's relaxed binder cannot bind map keys containing spaces and slashes (e.g.
 * {@code "PUT /customers/{id}/profile"}) from flat {@code application.yml} strings, so the rules
 * are registered programmatically here.
 *
 * <h2>Why {@code @PostConstruct} and not {@code ApplicationReadyEvent}</h2>
 * <p>The rules must already exist before the embedded server starts accepting traffic.
 * {@code ApplicationReadyEvent} is published <em>after</em> the web server has been started and
 * bound its port, which leaves a window between context refresh and that event in which
 * {@code RbacEnforcementFilter} sees an <em>empty</em> allow-list. An empty allow-list means no
 * pattern matches, and the filter's documented behaviour for "no match" is to pass the request
 * through — so during that window every customer endpoint would be open to any caller holding a
 * merely <em>valid</em> token, regardless of role. Registering in {@code @PostConstruct} closes the
 * window: the map is fully populated during context refresh, long before the first request can be
 * dispatched.
 *
 * <h2>Ordering and role semantics</h2>
 * <p>{@code RbacEnforcementFilter} takes the <strong>first matching entry</strong> in insertion
 * order ({@link RbacProperties} is backed by a {@code LinkedHashMap}), so specific patterns must be
 * registered before any broader one. Each rule's role list is a <strong>logical OR</strong>: a
 * caller holding <em>any</em> one of the listed roles (compared against the authority
 * {@code ROLE_<NAME>}) is allowed through. Role membership alone is not ownership — a
 * {@code CUSTOMER} holding the right role still has to be the owner of the {@code {id}} in the
 * path, which {@link com.homefix.customer.api.CallerIdentity} enforces in the controllers.
 *
 * <h2>Endpoints deliberately left without a rule</h2>
 * <p>The filter is installed <em>inside</em> the {@code SecurityFilterChain} (see
 * {@link WebSecurityConfig}) via {@code addFilterAfter(...)}, so it runs for every request that
 * reaches the chain — including the ones {@code authorizeHttpRequests} marked {@code permitAll()}.
 * A rule that matched a public path would therefore break it with a 401. The operational surface is
 * consequently left with <strong>no</strong> RBAC rule on purpose, and no registered pattern can
 * match it:
 * <ul>
 *   <li>{@code /health/**} — Kubernetes liveness/readiness probes, which present no token.</li>
 *   <li>{@code /actuator/**} — actuator endpoints including {@code /actuator/health}, scraped and
 *       probed without credentials.</li>
 *   <li>{@code /metrics} and {@code /prometheus} — Prometheus scrape targets (Requirement 25.4),
 *       also unauthenticated.</li>
 * </ul>
 * For this reason no catch-all such as {@code /customers/**} is registered either: every rule names
 * an explicit method and an explicit sub-path, so an endpoint added later fails closed in review
 * rather than silently inheriting a rule.
 */
@Configuration
public class CustomerRbacConfig {

    /**
     * The customer themselves plus the staff roles that legitimately act on a customer's behalf.
     * {@code SUPPORT_AGENT} is included because support handles profile/address corrections raised
     * through the help desk.
     */
    private static final List<String> CUSTOMER_SELF_SERVICE_TIER =
            List.of("CUSTOMER", "ADMIN", "SUPER_ADMIN", "SUPPORT_AGENT");

    /**
     * Data-deletion requests are irreversible (Requirement 26.8, 26.9), so they are restricted to
     * the customer themselves and the admin tier — deliberately <em>excluding</em>
     * {@code SUPPORT_AGENT}.
     */
    private static final List<String> CUSTOMER_AND_ADMIN_TIER =
            List.of("CUSTOMER", "ADMIN", "SUPER_ADMIN");

    private final RbacProperties rbacProperties;

    public CustomerRbacConfig(RbacProperties rbacProperties) {
        this.rbacProperties = rbacProperties;
    }

    /**
     * Registers the per-endpoint role requirements. {@code public} so tests can drive it directly
     * against a plain {@link RbacProperties} instance without starting a Spring context.
     */
    @PostConstruct
    public void registerEndpointRoles() {
        var rules = rbacProperties.getEndpointRoles();
        rules.put("PUT /customers/*/profile", CUSTOMER_SELF_SERVICE_TIER);
        rules.put("POST /customers/*/addresses", CUSTOMER_SELF_SERVICE_TIER);
        rules.put("DELETE /customers/*/addresses/**", CUSTOMER_SELF_SERVICE_TIER);
        rules.put("POST /customers/*/location/detect", CUSTOMER_SELF_SERVICE_TIER);
        rules.put("POST /customers/*/deletion", CUSTOMER_AND_ADMIN_TIER);
    }
}
