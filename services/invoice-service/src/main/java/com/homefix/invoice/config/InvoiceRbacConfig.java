package com.homefix.invoice.config;

import java.util.List;

import org.springframework.context.annotation.Configuration;

import com.homefix.shared.security.RbacProperties;

import jakarta.annotation.PostConstruct;

/**
 * Populates the shared {@link RbacProperties} allow-list for the Invoice Service (Requirement
 * 19.6).
 *
 * <p>Spring's relaxed binder cannot bind map keys containing spaces and slashes (e.g.
 * {@code "GET /invoices/customers/**"}) from flat {@code application.yml} strings, so the rules are
 * added programmatically here.
 *
 * <h2>Why {@code @PostConstruct} and not {@code ApplicationReadyEvent}</h2>
 * <p>The rules must be in place <em>before</em> the embedded servlet container starts accepting
 * traffic. {@code ApplicationReadyEvent} fires <em>after</em> the web server is listening, which
 * leaves a window between context refresh and that event in which the RBAC allow-list is still
 * empty — and an empty allow-list means {@code RbacEnforcementFilter} finds no matching rule and
 * passes every request through, so every endpoint is open to any caller holding a merely valid
 * token. Registering during bean initialisation closes that window.
 *
 * <h2>Semantics of the map</h2>
 * <ul>
 *   <li><strong>Ordering matters.</strong> {@code RbacEnforcementFilter} uses the <em>first</em>
 *       matching entry of this insertion-ordered map, so specific patterns must be registered before
 *       broader ones. The two entries below are disjoint prefixes, but the order is still meaningful
 *       and no catch-all {@code /invoices/**} entry is registered, so a future endpoint must be
 *       added here explicitly rather than inheriting someone else's roles.</li>
 *   <li><strong>The role list is a logical OR.</strong> Holding any one of the listed roles is
 *       sufficient.</li>
 * </ul>
 *
 * <h2>Endpoints deliberately left WITHOUT a rule</h2>
 * <p>The shared {@code RbacEnforcementFilter} is installed <em>inside</em> the
 * {@code SecurityFilterChain} (see {@link WebSecurityConfig}), so it runs even for requests that
 * {@code authorizeHttpRequests} marked {@code permitAll()}. A rule matching a public path would
 * therefore reject it with a 401. Consequently <strong>health, metrics and actuator get no
 * rule</strong>: {@code /health/**}, {@code /actuator/**}, {@code /metrics} and {@code /prometheus}
 * are scraped by Kubernetes probes and Prometheus, which present no JWT. They are
 * {@code permitAll()} in {@link WebSecurityConfig} and must stay rule-free. The Invoice Service
 * exposes no other public endpoint — invoice PDFs are served through pre-signed storage URLs rather
 * than a service route, so there is nothing else to exempt.
 *
 * <p>Role membership alone is not enough: a CUSTOMER matching {@code GET /invoices/customers/**}
 * could still pass another customer's id in the path. Ownership is enforced on top of these rules by
 * {@code CallerIdentity} in {@code InvoiceController}.
 */
@Configuration
public class InvoiceRbacConfig {

    /** Customer invoice history: the customer themselves, plus staff acting on their behalf. */
    private static final List<String> CUSTOMER_HISTORY_TIER = List.of(
            "CUSTOMER", "ADMIN", "SUPER_ADMIN", "FINANCE_ADMIN", "SUPPORT_AGENT");

    /** Provider earnings statements: the provider themselves, plus staff. */
    private static final List<String> PROVIDER_STATEMENT_TIER = List.of(
            "SERVICE_PROVIDER", "ADMIN", "SUPER_ADMIN", "FINANCE_ADMIN", "SUPPORT_AGENT");

    private final RbacProperties rbacProperties;

    public InvoiceRbacConfig(RbacProperties rbacProperties) {
        this.rbacProperties = rbacProperties;
    }

    /**
     * Registers the endpoint → roles allow-list. Public so tests can drive it directly against a
     * plain {@link RbacProperties} instance without starting a Spring context.
     */
    @PostConstruct
    public void registerEndpointRoles() {
        var rules = rbacProperties.getEndpointRoles();
        rules.put("GET /invoices/customers/**", CUSTOMER_HISTORY_TIER);
        rules.put("GET /invoices/providers/**", PROVIDER_STATEMENT_TIER);
        // Intentionally NO rule for /health/**, /actuator/**, /metrics, /prometheus, and no
        // catch-all for /invoices/**. See the class Javadoc.
    }
}
