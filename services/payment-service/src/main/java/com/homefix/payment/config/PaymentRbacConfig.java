package com.homefix.payment.config;

import java.util.List;

import org.springframework.context.annotation.Configuration;

import com.homefix.shared.security.RbacProperties;

import jakarta.annotation.PostConstruct;

/**
 * Populates the shared {@link RbacProperties} allow-list for the Payment Service (Requirement
 * 19.6).
 *
 * <p>Spring's relaxed binder cannot bind map keys containing spaces and slashes (e.g.
 * {@code "POST /payments/settlements"}) from flat {@code application.yml} strings, so the rules are
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
 *       matching entry of this insertion-ordered map, so the most specific patterns are registered
 *       before the broader ones.</li>
 *   <li><strong>The role list is a logical OR.</strong> Holding any one of the listed roles is
 *       sufficient.</li>
 * </ul>
 *
 * <h2>Endpoints deliberately left WITHOUT a rule</h2>
 * <p>The shared {@code RbacEnforcementFilter} is installed <em>inside</em> the
 * {@code SecurityFilterChain} (see {@link WebSecurityConfig}), so it runs even for requests that
 * {@code authorizeHttpRequests} marked {@code permitAll()}. A rule that matches a public path would
 * therefore reject that public path with a 401. Consequently:
 *
 * <ul>
 *   <li><strong>{@code POST /payments/callbacks/**} has no rule, on purpose.</strong> It is the
 *       public payment-gateway callback webhook: the gateway holds no HomeFix JWT, so the request
 *       arrives unauthenticated and is instead authenticated cryptographically by HMAC signature
 *       verification inside {@code PaymentService#handleGatewayCallback} (Requirement 12.5).
 *       {@code WebSecurityConfig} already {@code permitAll()}s it. Any rule matching it — in
 *       particular a catch-all {@code POST /payments/**} — would make the filter demand an
 *       authenticated principal and break every gateway callback with a 401, silently stranding
 *       payments in {@code PENDING}. <em>That is why there is no catch-all entry below.</em></li>
 *   <li>None of the registered patterns can match the callback path:
 *       <ul>
 *         <li>{@code POST /payments} is a bare pattern with no wildcard at all, so it matches only
 *             the exact path {@code /payments} and never {@code /payments/callbacks/{id}};</li>
 *         <li>{@code POST /payments/*}{@code /retries} and {@code POST /payments/*}{@code /refunds}
 *             have a fixed literal last segment, and the callback's second segment is the
 *             transaction id rather than {@code retries}/{@code refunds}, so neither can match
 *             {@code /payments/callbacks/{transactionId}};</li>
 *         <li>{@code POST /payments/settlements} is an exact literal path;</li>
 *         <li>{@code GET /payments/*} is scoped to GET and to a single path segment, whereas the
 *             callback is a two-segment POST.</li>
 *       </ul>
 *   </li>
 *   <li><strong>Health, metrics and actuator get no rule</strong> either — {@code /health/**},
 *       {@code /actuator/**}, {@code /metrics} and {@code /prometheus} are scraped by Kubernetes
 *       probes and Prometheus, which present no JWT; they are {@code permitAll()} in
 *       {@link WebSecurityConfig} and must stay rule-free for the same reason as above.</li>
 * </ul>
 *
 * <p>Ownership (a customer reading only <em>their own</em> transaction) is orthogonal to role
 * membership and is enforced separately by {@code CallerIdentity} in the controller.
 */
@Configuration
public class PaymentRbacConfig {

    /** Roles permitted to move money: refunds and provider settlements (Requirement 14.3). */
    private static final List<String> FINANCE_TIER =
            List.of("ADMIN", "SUPER_ADMIN", "FINANCE_ADMIN");

    /** Customer-driven retry, plus the staff roles that may retry on a customer's behalf. */
    private static final List<String> RETRY_TIER =
            List.of("CUSTOMER", "ADMIN", "SUPER_ADMIN", "FINANCE_ADMIN", "SUPPORT_AGENT");

    /** Roles permitted to open a payment (customer-facing checkout, or staff acting for them). */
    private static final List<String> INITIATE_TIER =
            List.of("CUSTOMER", "ADMIN", "SUPER_ADMIN", "SUPPORT_AGENT");

    /** Roles permitted to read a single transaction; ownership is checked on top of this. */
    private static final List<String> READ_TIER = List.of(
            "CUSTOMER", "SERVICE_PROVIDER", "ADMIN", "SUPER_ADMIN", "FINANCE_ADMIN",
            "SUPPORT_AGENT");

    private final RbacProperties rbacProperties;

    public PaymentRbacConfig(RbacProperties rbacProperties) {
        this.rbacProperties = rbacProperties;
    }

    /**
     * Registers the endpoint → roles allow-list. Public so tests can drive it directly against a
     * plain {@link RbacProperties} instance without starting a Spring context.
     */
    @PostConstruct
    public void registerEndpointRoles() {
        var rules = rbacProperties.getEndpointRoles();
        // Most specific money operations first.
        rules.put("POST /payments/settlements", FINANCE_TIER);
        rules.put("POST /payments/*/refunds", FINANCE_TIER);
        rules.put("POST /payments/*/retries", RETRY_TIER);
        // Bare pattern: matches exactly /payments, never /payments/callbacks/{id}.
        rules.put("POST /payments", INITIATE_TIER);
        // Single-segment GET: matches /payments/{transactionId} only.
        rules.put("GET /payments/*", READ_TIER);
        // Intentionally NO rule for POST /payments/callbacks/** (public, HMAC-verified) and none
        // for /health/**, /actuator/**, /metrics, /prometheus. See the class Javadoc.
    }
}
