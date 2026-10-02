package com.homefix.promotion.config;

import java.util.List;

import org.springframework.context.annotation.Configuration;

import com.homefix.shared.security.RbacProperties;

import jakarta.annotation.PostConstruct;

/**
 * Populates the shared {@link RbacProperties} allow-list for the Promotion / Coupon Service
 * (Requirement 19.6, 21).
 *
 * <p>Spring's relaxed binder cannot bind map keys containing spaces and slashes (e.g.
 * {@code "POST /coupons/&#42;/activate"}) from flat {@code application.yml} strings, so the rules are
 * registered programmatically here.
 *
 * <h2>Why {@code @PostConstruct} and not {@code ApplicationReadyEvent}</h2>
 * <p>The rules must exist <em>before</em> the embedded server starts accepting traffic. Registering
 * them on {@code ApplicationReadyEvent} leaves a window between context refresh (when the web
 * server is already bound and serving) and the event firing, during which
 * {@code RbacEnforcementFilter} finds an empty allow-list; because the filter passes through when
 * no pattern matches, <em>every</em> endpoint — coupon creation and deactivation included — would
 * be open to any caller holding a valid token. {@code @PostConstruct} runs during context refresh,
 * before the server accepts requests, so there is no such window. The method is {@code public} so
 * tests can drive it directly.
 *
 * <h2>Semantics</h2>
 * <ul>
 *   <li><strong>Ordering matters.</strong> {@code RbacEnforcementFilter} uses the <em>first</em>
 *       matching entry in this {@code LinkedHashMap}, so specific patterns must be inserted before
 *       broader ones.</li>
 *   <li><strong>The role list is a logical OR.</strong> A caller holding <em>any</em> one of the
 *       listed roles is admitted; the authority compared is {@code ROLE_<NAME>}.</li>
 * </ul>
 *
 * <h2>Ordering around the bare {@code POST /coupons}</h2>
 * <p>{@code POST /coupons} is the Admin create endpoint (Requirement 21.1). It is written as a
 * <em>bare, wildcard-free</em> pattern, so Ant matching makes it match that one exact path and
 * nothing else: it cannot swallow {@code /coupons/validate}, {@code /coupons/redeem} or
 * {@code /coupons/cancel}, which would otherwise become Admin-only and lock every customer out of
 * checkout. Nevertheless the customer-facing redemption paths and the Admin activation paths are
 * declared <em>ahead</em> of it, as defence in depth: should someone later widen the create rule to
 * {@code POST /coupons/**} (an easy and plausible edit), first-match-wins still resolves
 * {@code /validate}, {@code /redeem}, {@code /cancel} to the customer tier and
 * {@code /{id}/activate}, {@code /{id}/deactivate} to the Admin tier instead of silently breaking
 * checkout. For the same reason {@code GET /coupons/code/*} precedes the more general
 * {@code GET /coupons/*} — otherwise the single-segment wildcard would not match the two-segment
 * code lookup at all and it would fall through with no rule.
 *
 * <h2>Ownership, which roles alone cannot express</h2>
 * <p>{@code /coupons/validate}, {@code /coupons/redeem} and {@code /coupons/cancel} all take a
 * {@code userId} in the request <em>body</em>, so the role rules here cannot tell whose allowance is
 * being spent. A {@code CUSTOMER} admitted by these rules is additionally checked against that body
 * field by {@code CallerIdentity.requireSelfOrStaff(...)} in
 * {@code com.homefix.promotion.api.CouponController}, so one customer cannot burn or probe
 * another's per-user coupon limit.
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
public class PromotionRbacConfig {

    /**
     * Staff tier permitted to create and (de)activate coupons and to use the Admin Portal coupon
     * screens (logical OR).
     */
    private static final List<String> ADMIN_TIER = List.of("ADMIN", "SUPER_ADMIN");

    /**
     * Checkout-time validation, redemption and cancellation: the customer themselves, plus staff
     * acting on a customer's behalf. Ownership of the body {@code userId} is asserted separately in
     * the controller.
     */
    private static final List<String> REDEMPTION_TIER =
            List.of("CUSTOMER", "ADMIN", "SUPER_ADMIN", "SUPPORT_AGENT");

    /** Coupon reads: customers and providers quoting work, plus staff. */
    private static final List<String> READ_TIER =
            List.of("CUSTOMER", "SERVICE_PROVIDER", "ADMIN", "SUPER_ADMIN", "SUPPORT_AGENT");

    private final RbacProperties rbacProperties;

    public PromotionRbacConfig(RbacProperties rbacProperties) {
        this.rbacProperties = rbacProperties;
    }

    /**
     * Registers the coupon endpoint → role rules in first-match-wins order: activation, then the
     * redemption paths, then the bare Admin create, then the reads. Deliberately adds no entry for
     * the health/actuator surface — see the class Javadoc.
     */
    @PostConstruct
    public void registerEndpointRoles() {
        var rules = rbacProperties.getEndpointRoles();
        // Admin Portal coupon management (Requirement 19.2): list, create, deactivate. A separate
        // prefix, so these cannot widen any /coupons rule below; "/**" also matches the bare
        // "/admin/coupons" collection path.
        rules.put("GET /admin/coupons/**", ADMIN_TIER);
        rules.put("POST /admin/coupons/**", ADMIN_TIER);
        rules.put("PATCH /admin/coupons/**", ADMIN_TIER);
        // Admin lifecycle actions on a specific coupon (Requirement 21.4).
        rules.put("POST /coupons/*/activate", ADMIN_TIER);
        rules.put("POST /coupons/*/deactivate", ADMIN_TIER);
        // Customer checkout flow (Requirement 21.2, 21.3, 21.5) — declared before the bare create.
        rules.put("POST /coupons/validate", REDEMPTION_TIER);
        rules.put("POST /coupons/redeem", REDEMPTION_TIER);
        rules.put("POST /coupons/cancel", REDEMPTION_TIER);
        // Admin create (Requirement 21.1): bare pattern, matches only the exact collection path.
        rules.put("POST /coupons", ADMIN_TIER);
        // Reads: the two-segment code lookup before the single-segment id lookup.
        rules.put("GET /coupons/code/*", READ_TIER);
        rules.put("GET /coupons/*", READ_TIER);
    }
}
