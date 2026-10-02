package com.homefix.complaint.config;

import java.util.List;

import org.springframework.context.annotation.Configuration;

import com.homefix.shared.security.RbacProperties;

import jakarta.annotation.PostConstruct;

/**
 * Populates the shared {@link RbacProperties} allow-list for the Complaint Service
 * (Requirement 16, Requirement 19.6).
 *
 * <p>Spring's relaxed binder cannot bind map keys containing spaces and slashes (e.g.
 * a single-segment wildcard followed by {@code /refund}) from flat {@code application.yml}
 * strings, so the rules are registered programmatically here.
 *
 * <h2>Why {@code @PostConstruct} and not {@code ApplicationReadyEvent}</h2>
 * <p>The rules must exist <em>before</em> the embedded server starts accepting traffic. Registering
 * them on {@code ApplicationReadyEvent} leaves a window between context refresh (when the web
 * server is already bound and serving) and the event firing, during which
 * {@code RbacEnforcementFilter} finds an empty allow-list; because the filter passes through when
 * no pattern matches, <em>every</em> complaint endpoint — including refund approval, which moves
 * money — would be open to any caller holding a valid token, whatever their role.
 * {@code @PostConstruct} runs during context refresh, before the server accepts requests, so there
 * is no such window. The method is {@code public} so tests can drive it directly.
 *
 * <h2>Semantics</h2>
 * <ul>
 *   <li><strong>Ordering matters.</strong> {@code RbacEnforcementFilter} uses the <em>first</em>
 *       matching entry in this {@code LinkedHashMap}, so more specific patterns must be inserted
 *       before any catch-all or broader pattern.</li>
 *   <li><strong>The role list is a logical OR.</strong> A caller holding <em>any</em> one of the
 *       listed roles is admitted; the authority compared is {@code ROLE_<NAME>}.</li>
 * </ul>
 *
 * <h2>Ordering in this service specifically</h2>
 * <p>{@code POST /complaints} is a bare, wildcard-free pattern. {@code AntPathMatcher} will not let
 * it match {@code /complaints/{complaintId}/status}, {@code .../refund} or {@code .../dispute}, so
 * as written it cannot swallow the staff-only moderation paths and widen them to CUSTOMER. The four
 * staff rules are nonetheless declared <em>first</em> as defence in depth: if somebody later
 * broadens the customer rule to {@code POST /complaints/**} (an easy, plausible edit), first-match
 * ordering keeps status change, refund approval, dispute and the stats report staff-only instead of
 * silently handing them to every customer.
 *
 * <h2>Ownership</h2>
 * <p>There is no {@code {userId}}-in-path ownership dimension to enforce in this service.
 * {@code POST /complaints} attributes the new complaint to the JWT subject via the controller's
 * private {@code currentUser()} helper, and {@code CreateComplaintRequest} carries no
 * customer/reporter id a client could substitute. The remaining handlers take only a
 * {@code {complaintId}} — a complaint id, not a user id — and are restricted to staff by role, so
 * role is the whole authorization decision for them.
 *
 * <h2>Endpoints deliberately left without a rule</h2>
 * <p>{@code RbacEnforcementFilter} is installed <em>inside</em> the {@code SecurityFilterChain}
 * (see {@link WebSecurityConfig}) via {@code addFilterAfter(...)}, so it runs for every request
 * that reaches the chain — including those that {@code authorizeHttpRequests} marked
 * {@code permitAll()}. A rule matching a public path therefore turns that public path into a 401
 * for anonymous callers. One surface is consequently left unmapped on purpose:
 * <ul>
 *   <li><strong>The health / metrics surface</strong> ({@code /health/**}, {@code /actuator/**},
 *       {@code /metrics}, {@code /prometheus}) — scraped by Kubernetes probes and Prometheus,
 *       which present no bearer token. Any rule covering these paths, even one listing every
 *       platform role, would fail every liveness and readiness probe with a 401 and take the
 *       service out of rotation.</li>
 * </ul>
 */
@Configuration
public class ComplaintRbacConfig {

    /**
     * Staff tier permitted to moderate complaints: change status (16.3), approve refunds (16.5),
     * mark disputes (16.7), read aggregated statistics (16.9) and work the Admin Portal complaint
     * list (19.2). Logical OR.
     */
    private static final List<String> SUPPORT_TIER =
            List.of("ADMIN", "SUPER_ADMIN", "SUPPORT_AGENT");

    /**
     * Roles permitted to raise a complaint (16.1): the customer themselves, plus staff raising one
     * on a customer's behalf from the support console. Logical OR.
     */
    private static final List<String> COMPLAINT_AUTHORS =
            List.of("CUSTOMER", "ADMIN", "SUPER_ADMIN", "SUPPORT_AGENT");

    private final RbacProperties rbacProperties;

    public ComplaintRbacConfig(RbacProperties rbacProperties) {
        this.rbacProperties = rbacProperties;
    }

    /**
     * Registers the complaint endpoint → role rules. Deliberately adds no entry for the
     * health/actuator/metrics surface — see the class Javadoc. Insertion order is the evaluation
     * order: staff-only paths first, the customer-facing create last.
     */
    @PostConstruct
    public void registerEndpointRoles() {
        var rules = rbacProperties.getEndpointRoles();
        // Admin Portal complaint management (19.2): the list and the status/note update. The
        // "/**" form also matches the bare "/admin/complaints" list path.
        rules.put("GET /admin/complaints/**", SUPPORT_TIER);
        rules.put("PATCH /admin/complaints/**", SUPPORT_TIER);
        // Staff reporting surface (16.9) — declared before anything that could widen it.
        rules.put("GET /complaints/stats", SUPPORT_TIER);
        // Staff moderation of an individual complaint (16.3, 16.5/16.6, 16.7).
        rules.put("POST /complaints/*/status", SUPPORT_TIER);
        rules.put("POST /complaints/*/refund", SUPPORT_TIER);
        rules.put("POST /complaints/*/dispute", SUPPORT_TIER);
        // Customer-facing complaint creation (16.1); the customer id comes from the JWT, never
        // from the body. Declared last so it can never shadow a staff path above.
        rules.put("POST /complaints", COMPLAINT_AUTHORS);
    }
}
