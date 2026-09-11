package com.homefix.pricing.config;

import java.util.List;

import org.springframework.context.annotation.Configuration;

import com.homefix.shared.security.RbacProperties;

import jakarta.annotation.PostConstruct;

/**
 * Populates the shared {@link RbacProperties} allow-list for the Pricing Engine
 * (Requirement 19.6).
 *
 * <p>Spring's relaxed binder cannot bind map keys containing spaces and slashes (e.g.
 * {@code "PUT /admin/**"}) from flat {@code application.yml} strings, so the rules are registered
 * programmatically here.
 *
 * <h2>Why {@code @PostConstruct} and not {@code ApplicationReadyEvent}</h2>
 * <p>The rules must exist <em>before</em> the embedded server starts accepting traffic. Registering
 * them on {@code ApplicationReadyEvent} leaves a window between context refresh (when the web
 * server is already bound and serving) and the event firing, during which
 * {@code RbacEnforcementFilter} finds an empty allow-list; because the filter passes through when
 * no pattern matches, <em>every</em> endpoint — the Admin pricing-parameter writes included —
 * would be open to any caller holding a valid token. {@code @PostConstruct} runs during context
 * refresh, before the server accepts requests, so there is no such window. The method is
 * {@code public} so tests can drive it directly.
 *
 * <h2>Semantics</h2>
 * <ul>
 *   <li><strong>Ordering matters.</strong> {@code RbacEnforcementFilter} uses the <em>first</em>
 *       matching entry in this {@code LinkedHashMap}, so the specific {@code /pricing/...} rules
 *       are declared after the {@code /admin/**} block (which cannot match them) and the narrower
 *       {@code /pricing/overrides} rule is declared before the broader estimate rule.</li>
 *   <li><strong>The role list is a logical OR.</strong> A caller holding <em>any</em> one of the
 *       listed roles is admitted; the authority compared is {@code ROLE_<NAME>}.</li>
 * </ul>
 *
 * <h2>Why {@code POST /pricing/estimate} admits more than CUSTOMER</h2>
 * <p>{@code POST /pricing/estimate} is not only called by customers directly. The Booking Service
 * calls it server-to-server while handling a booking request and <em>relays the end user's bearer
 * token</em> rather than using a service identity (see
 * {@code services/booking-service/.../client/BearerTokenRelay.java}). The principal this filter
 * sees on such a call is therefore whoever drove the booking, not a machine account. Bookings can
 * be driven by a customer for themselves, by a provider quoting or adjusting work, and by a
 * dispatcher booking on a customer's behalf, so the rule admits {@code CUSTOMER},
 * {@code SERVICE_PROVIDER} and {@code DISPATCHER} alongside the Admin tier. Narrowing it to
 * {@code CUSTOMER} would make every dispatcher- and provider-initiated booking fail with a 403 on
 * the downstream estimate call.
 *
 * <p>{@code POST /pricing/overrides} only validates a provider-specific price override against the
 * subcategory bounds (Requirement 6.12), so it is restricted to {@code SERVICE_PROVIDER} plus the
 * Admin tier — a customer has no business probing override bounds.
 *
 * <h2>Endpoints deliberately left without a rule</h2>
 * <p>{@code RbacEnforcementFilter} is installed <em>inside</em> the {@code SecurityFilterChain}
 * (see {@link WebSecurityConfig}) via {@code addFilterAfter(...)}, so it runs for every request
 * that reaches the chain — including those that {@code authorizeHttpRequests} marked
 * {@code permitAll()}. A rule matching a public path therefore turns that path into a 401 for
 * anonymous callers. The <strong>health / metrics surface</strong> ({@code /health/**},
 * {@code /actuator/**}, {@code /metrics}, {@code /prometheus}) is consequently left unmapped on
 * purpose: it is scraped by Kubernetes probes and Prometheus, which present no bearer token, and
 * any rule covering those paths would fail every liveness and readiness probe with a 401 and take
 * the service out of rotation.
 */
@Configuration
public class PricingRbacConfig {

    /** Staff tier permitted to administer pricing parameters (logical OR). */
    private static final List<String> ADMIN_TIER = List.of("ADMIN", "SUPER_ADMIN");

    /** Providers propose their own overrides; staff may validate anyone's. */
    private static final List<String> OVERRIDE_TIER =
            List.of("SERVICE_PROVIDER", "ADMIN", "SUPER_ADMIN");

    /**
     * Every role that can legitimately drive a booking and therefore appear as the relayed
     * principal on a price estimate.
     */
    private static final List<String> ESTIMATE_TIER =
            List.of("CUSTOMER", "SERVICE_PROVIDER", "DISPATCHER", "ADMIN", "SUPER_ADMIN");

    private final RbacProperties rbacProperties;

    public PricingRbacConfig(RbacProperties rbacProperties) {
        this.rbacProperties = rbacProperties;
    }

    /**
     * Registers the pricing endpoint → role rules in first-match-wins order. Deliberately adds no
     * entry for the health/actuator surface — see the class Javadoc.
     */
    @PostConstruct
    public void registerEndpointRoles() {
        var rules = rbacProperties.getEndpointRoles();
        // Admin pricing-parameter configuration (AdminPricingController, Requirement 6.11).
        rules.put("GET /admin/**", ADMIN_TIER);
        rules.put("POST /admin/**", ADMIN_TIER);
        rules.put("PUT /admin/**", ADMIN_TIER);
        rules.put("DELETE /admin/**", ADMIN_TIER);
        // Narrower provider-override rule before the broader estimate rule.
        rules.put("POST /pricing/overrides", OVERRIDE_TIER);
        // Called with the end user's relayed token by booking-service — see class Javadoc.
        rules.put("POST /pricing/estimate", ESTIMATE_TIER);
    }
}
