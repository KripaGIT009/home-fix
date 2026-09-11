package com.homefix.catalog.config;

import java.util.List;

import org.springframework.context.annotation.Configuration;

import com.homefix.shared.security.RbacProperties;

import jakarta.annotation.PostConstruct;

/**
 * Populates the shared {@link RbacProperties} allow-list for the Service Catalog Service
 * (Requirement 19.6).
 *
 * <p>Spring's relaxed binder cannot bind map keys containing spaces and slashes (e.g.
 * {@code "GET /admin/**"}) from flat {@code application.yml} strings, so the rules are registered
 * programmatically here.
 *
 * <h2>Why {@code @PostConstruct} and not {@code ApplicationReadyEvent}</h2>
 * <p>The rules must exist <em>before</em> the embedded server starts accepting traffic. Registering
 * them on {@code ApplicationReadyEvent} leaves a window between context refresh (when the web
 * server is already bound and serving) and the event firing, during which
 * {@code RbacEnforcementFilter} finds an empty allow-list; because the filter passes through when
 * no pattern matches, <em>every</em> Admin endpoint would be open to any caller holding a valid
 * token. {@code @PostConstruct} runs during context refresh, before the server accepts requests, so
 * there is no such window. The method is {@code public} so tests can drive it directly.
 *
 * <h2>Semantics</h2>
 * <ul>
 *   <li><strong>Ordering matters.</strong> {@code RbacEnforcementFilter} uses the <em>first</em>
 *       matching entry in this {@code LinkedHashMap}, so more specific patterns must be inserted
 *       before any catch-all.</li>
 *   <li><strong>The role list is a logical OR.</strong> A caller holding <em>any</em> one of the
 *       listed roles is admitted; the authority compared is {@code ROLE_<NAME>}.</li>
 * </ul>
 *
 * <h2>Endpoints deliberately left without a rule</h2>
 * <p>{@code RbacEnforcementFilter} is installed <em>inside</em> the {@code SecurityFilterChain}
 * (see {@link WebSecurityConfig}) via {@code addFilterAfter(...)}, so it runs for every request
 * that reaches the chain — including those that {@code authorizeHttpRequests} marked
 * {@code permitAll()}. A rule matching a public path therefore turns that public path into a 401
 * for anonymous callers. Two surfaces are consequently left unmapped on purpose:
 * <ul>
 *   <li><strong>{@code GET /catalog/**}</strong> — the customer-facing read-only catalog listing
 *       ({@code GET /catalog/categories}, Requirement 3.5/3.8). It is explicitly
 *       {@code permitAll()} in {@link WebSecurityConfig} and must stay reachable with no token at
 *       all, for anonymous browsing of the marketplace. Registering <em>any</em> rule for it —
 *       even one listing every platform role — would break it.</li>
 *   <li><strong>The health / metrics surface</strong> ({@code /health/**}, {@code /actuator/**},
 *       {@code /metrics}, {@code /prometheus}) — scraped by Kubernetes probes and Prometheus,
 *       which present no bearer token. Any rule covering these paths would fail every liveness
 *       and readiness probe with a 401 and take the service out of rotation.</li>
 * </ul>
 *
 * <p>Everything else under {@code /admin/**} (the Admin CRUD surface of
 * {@code AdminCatalogController}, Requirement 3.2–3.7) is restricted to the Admin tier. Catalog
 * paths carry no user, provider, or customer identifier, so there is no per-caller ownership
 * dimension to enforce here — role is the whole authorization decision.
 */
@Configuration
public class CatalogRbacConfig {

    /** Staff tier permitted to administer the catalog (logical OR). */
    private static final List<String> ADMIN_TIER = List.of("ADMIN", "SUPER_ADMIN");

    private final RbacProperties rbacProperties;

    public CatalogRbacConfig(RbacProperties rbacProperties) {
        this.rbacProperties = rbacProperties;
    }

    /**
     * Registers the catalog endpoint → role rules. Deliberately adds no entry for
     * {@code GET /catalog/**} nor for the health/actuator surface — see the class Javadoc.
     */
    @PostConstruct
    public void registerEndpointRoles() {
        var rules = rbacProperties.getEndpointRoles();
        // Admin CRUD for categories and subcategories (AdminCatalogController).
        rules.put("GET /admin/**", ADMIN_TIER);
        rules.put("POST /admin/**", ADMIN_TIER);
        rules.put("PUT /admin/**", ADMIN_TIER);
        rules.put("DELETE /admin/**", ADMIN_TIER);
    }
}
