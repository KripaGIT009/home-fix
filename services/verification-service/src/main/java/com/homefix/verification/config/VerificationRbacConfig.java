package com.homefix.verification.config;

import java.util.List;

import org.springframework.context.annotation.Configuration;

import com.homefix.shared.security.RbacProperties;

import jakarta.annotation.PostConstruct;

/**
 * Populates the shared {@link RbacProperties} allow-list for the Provider Verification Service
 * (Requirement 19.6).
 *
 * <p>Spring's relaxed binder cannot bind map keys containing spaces and slashes (e.g.
 * {@code "POST /admin/**"}) from flat {@code application.yml} strings, so the rules are registered
 * programmatically here.
 *
 * <h2>Why {@code @PostConstruct} and not {@code ApplicationReadyEvent}</h2>
 * <p>The rules must exist <em>before</em> the embedded server starts accepting traffic. Registering
 * them on {@code ApplicationReadyEvent} leaves a window between context refresh (when the web
 * server is already bound and serving) and the event firing, during which
 * {@code RbacEnforcementFilter} finds an empty allow-list; because the filter passes through when
 * no pattern matches, <em>every</em> verification endpoint — including the Admin approve/reject/
 * suspend actions — would be open to any caller holding a valid token. {@code @PostConstruct} runs
 * during context refresh, before the server accepts requests, so there is no such window. The
 * method is {@code public} so tests can drive it directly.
 *
 * <h2>Semantics</h2>
 * <ul>
 *   <li><strong>Ordering matters.</strong> {@code RbacEnforcementFilter} uses the <em>first</em>
 *       matching entry in this {@code LinkedHashMap}, so more specific patterns must be inserted
 *       before any catch-all. The Admin surface is registered first, then the two narrow
 *       provider-facing paths, and only then the catch-all
 *       {@code GET /verifications/}&#42; read rule — registering that catch-all earlier would
 *       shadow the job-assignment-eligibility rule and lock {@code DISPATCHER} out of it.</li>
 *   <li><strong>The role list is a logical OR.</strong> A caller holding <em>any</em> one of the
 *       listed roles is admitted; the authority compared is {@code ROLE_<NAME>}.</li>
 *   <li><strong>Role is only half of the decision.</strong> The provider-facing paths carry a
 *       {@code {providerId}}, so a role check alone would let any principal holding
 *       {@code ROLE_SERVICE_PROVIDER} submit documents for, or read the verification record of,
 *       <em>another</em> provider. {@code VerificationController} therefore also asserts ownership
 *       via {@code CallerIdentity.requireSelfOrStaff(providerId)}.</li>
 * </ul>
 *
 * <h2>Why job-assignment-eligibility is readable by DISPATCHER and carries no ownership check</h2>
 * <p>{@code GET /verifications/}&#42;{@code /job-assignment-eligibility} is the gate the Dispatch
 * Engine calls before offering a job to a candidate provider (Requirement 5.10). The caller is
 * therefore, by design, <em>not</em> the provider being asked about, so {@code DISPATCHER} is an
 * admitted role and the handler deliberately carries no {@code requireSelfOrStaff} assertion. It
 * leaks nothing beyond a boolean eligibility verdict (expressed as 204 vs 403), unlike
 * {@code GET /verifications/}&#42;, which returns the full record including document references and
 * the background-check result and so is both ownership-checked and restricted to staff.
 *
 * <h2>Endpoints deliberately left without a rule</h2>
 * <p>{@code RbacEnforcementFilter} is installed <em>inside</em> the {@code SecurityFilterChain}
 * (see {@link WebSecurityConfig}) via {@code addFilterAfter(...)}, so it runs for every request
 * that reaches the chain — including those that {@code authorizeHttpRequests} marked
 * {@code permitAll()}. A rule matching a public path therefore turns that public path into a 401
 * for anonymous callers. Two surfaces are consequently left unmapped on purpose:
 * <ul>
 *   <li><strong>The service-to-service surface</strong> ({@code /internal/**}, the Provider
 *       Service's batch {@code APPROVED} lookup) — its caller is a service presenting
 *       {@code X-Internal-Api-Key}, not a user with roles. It is authorised by
 *       {@code InternalApiKeyFilter} and the {@code ROLE_INTERNAL} requirement in
 *       {@link WebSecurityConfig}; a role rule here would refuse it, since the internal principal
 *       holds no platform role.</li>
 *   <li><strong>The health / metrics surface</strong> ({@code /health/**}, {@code /actuator/**},
 *       {@code /metrics}, {@code /prometheus}) — scraped by Kubernetes probes and Prometheus,
 *       which present no bearer token. Any rule covering these paths would fail every liveness
 *       and readiness probe with a 401 and take the service out of rotation.</li>
 * </ul>
 */
@Configuration
public class VerificationRbacConfig {

    /** Staff tier permitted to drive the verification state machine (logical OR). */
    private static final List<String> ADMIN_TIER = List.of("ADMIN", "SUPER_ADMIN");

    /** Provider self-service document upload, plus staff acting on a provider's behalf. */
    private static final List<String> DOCUMENT_SUBMIT_TIER =
            List.of("SERVICE_PROVIDER", "ADMIN", "SUPER_ADMIN");

    /**
     * The dispatch gate: the Dispatch Engine asks about <em>other</em> providers, so
     * {@code DISPATCHER} is admitted here and nowhere else in this service.
     */
    private static final List<String> ELIGIBILITY_READ_TIER =
            List.of("SERVICE_PROVIDER", "DISPATCHER", "ADMIN", "SUPER_ADMIN");

    /**
     * Full verification record readers: the provider themselves plus staff, including
     * {@code SUPPORT_AGENT} who fields "where is my application?" queries.
     */
    private static final List<String> RECORD_READ_TIER =
            List.of("SERVICE_PROVIDER", "ADMIN", "SUPER_ADMIN", "SUPPORT_AGENT");

    private final RbacProperties rbacProperties;

    public VerificationRbacConfig(RbacProperties rbacProperties) {
        this.rbacProperties = rbacProperties;
    }

    /**
     * Registers the verification endpoint → role rules. Deliberately adds no entry for the
     * health/actuator or {@code /internal/**} surfaces — see the class Javadoc.
     */
    @PostConstruct
    public void registerEndpointRoles() {
        var rules = rbacProperties.getEndpointRoles();
        // Admin verification workflow: verify-documents, background-check-result, approve, reject,
        // suspend (Requirements 5.4-5.9). Registered first, as the most privileged surface.
        // The Admin Portal's Verification Queue (Requirement 19.3): the review queue, a provider's
        // documents and the approve/reject decision. The catch-alls below would cover them too;
        // they are pinned explicitly so narrowing a catch-all can never open them.
        rules.put("GET /admin/verification/**", ADMIN_TIER);
        rules.put("POST /admin/verification/**", ADMIN_TIER);
        rules.put("GET /admin/**", ADMIN_TIER);
        rules.put("POST /admin/**", ADMIN_TIER);
        rules.put("PUT /admin/**", ADMIN_TIER);
        // No PATCH handler exists today; the rule closes the gap so one added later is never open
        // to any authenticated caller by default.
        rules.put("PATCH /admin/**", ADMIN_TIER);
        rules.put("DELETE /admin/**", ADMIN_TIER);
        // Provider document upload (Requirement 5.3).
        rules.put("POST /verifications/*/documents", DOCUMENT_SUBMIT_TIER);
        // Dispatch eligibility gate (Requirement 5.10) — must precede the catch-all GET rule below,
        // otherwise DISPATCHER would be shadowed out of it.
        rules.put("GET /verifications/*/job-assignment-eligibility", ELIGIBILITY_READ_TIER);
        // Full record read — catch-all for the remaining provider-facing GET.
        rules.put("GET /verifications/*", RECORD_READ_TIER);
    }
}
