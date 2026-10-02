package com.homefix.provider.config;

import java.util.List;

import org.springframework.context.annotation.Configuration;

import com.homefix.shared.security.RbacProperties;

import jakarta.annotation.PostConstruct;

/**
 * Populates the shared {@link RbacProperties} allow-list for the Provider Service
 * (Requirement 19.6).
 *
 * <p>Spring's relaxed binder cannot bind map keys containing spaces and slashes (e.g.
 * {@code "PUT /providers/**"}) from flat {@code application.yml} strings, so the rules are
 * registered programmatically here.
 *
 * <h2>Why {@code @PostConstruct} and not {@code ApplicationReadyEvent}</h2>
 * <p>The rules must exist <em>before</em> the embedded server starts accepting traffic. Registering
 * them on {@code ApplicationReadyEvent} leaves a window between context refresh (when the web
 * server is already bound and serving) and the event firing, during which
 * {@code RbacEnforcementFilter} finds an empty allow-list; because the filter passes through when
 * no pattern matches, <em>every</em> provider endpoint would be open to any caller holding a valid
 * token. {@code @PostConstruct} runs during context refresh, before the server accepts requests, so
 * there is no such window. The method is {@code public} so tests can drive it directly.
 *
 * <h2>Semantics</h2>
 * <ul>
 *   <li><strong>Ordering matters.</strong> {@code RbacEnforcementFilter} uses the <em>first</em>
 *       matching entry in this {@code LinkedHashMap}, so more specific patterns must be inserted
 *       before any catch-all. {@code PUT /providers/**} is the catch-all for every mutating
 *       profile/availability endpoint and is listed first among them precisely because no narrower
 *       {@code PUT} rule exists; the {@code POST} and {@code GET} rules that follow cannot be
 *       shadowed by it because the filter also compares the HTTP method. The Admin Portal rules
 *       ({@code /admin/providers/**}) come before all of them; they share no path prefix with
 *       {@code /providers/**}, so the relative order is immaterial.</li>
 *   <li><strong>The role list is a logical OR.</strong> A caller holding <em>any</em> one of the
 *       listed roles is admitted; the authority compared is {@code ROLE_<NAME>}.</li>
 *   <li><strong>Role is only half of the decision.</strong> Every rule below is a coarse
 *       role gate over a path that carries a {@code {id}} provider identifier. The per-caller
 *       ownership dimension — "is this provider acting on their <em>own</em> record?" — is enforced
 *       in {@code ProviderController} via
 *       {@code CallerIdentity.requireSelfOrStaff(id)}. Without that assertion, any principal
 *       holding {@code ROLE_SERVICE_PROVIDER} would satisfy these rules for <em>another</em>
 *       provider's id.</li>
 * </ul>
 *
 * <h2>Why DISPATCHER may read a provider profile</h2>
 * <p>{@code GET /providers/&#42;/profile} deliberately admits {@code DISPATCHER} (and
 * {@code SUPPORT_AGENT}): dispatch needs the provider's categories, skills, service radius, and
 * availability to score and offer jobs, and support needs it to answer customer queries. That is
 * safe to broaden here because the ownership assertion in the controller is what keeps one provider
 * out of another provider's record — the role list never grants a {@code SERVICE_PROVIDER}
 * cross-provider reach.
 *
 * <h2>Endpoints deliberately left without a rule</h2>
 * <p>{@code RbacEnforcementFilter} is installed <em>inside</em> the {@code SecurityFilterChain}
 * (see {@link WebSecurityConfig}) via {@code addFilterAfter(...)}, so it runs for every request
 * that reaches the chain — including those that {@code authorizeHttpRequests} marked
 * {@code permitAll()}. A rule matching a public path therefore turns that public path into a 401
 * for anonymous callers. Two surfaces are consequently left unmapped on purpose:
 * <ul>
 *   <li><strong>The service-to-service surface</strong> ({@code /internal/**}, the Dispatch Engine's
 *       eligible-provider search) — its caller is a service presenting {@code X-Internal-Api-Key},
 *       not a user with roles. It is authorised by {@code InternalApiKeyFilter} and the
 *       {@code ROLE_INTERNAL} requirement in {@link WebSecurityConfig}; a role rule here would
 *       refuse it, since the internal principal holds no platform role.</li>
 *   <li><strong>The health / metrics surface</strong> ({@code /health/**}, {@code /actuator/**},
 *       {@code /metrics}, {@code /prometheus}) — scraped by Kubernetes probes and Prometheus,
 *       which present no bearer token. Any rule covering these paths would fail every liveness
 *       and readiness probe with a 401 and take the service out of rotation.</li>
 * </ul>
 */
@Configuration
public class ProviderRbacConfig {

    /**
     * Provider self-service plus platform staff oversight: the tier allowed to mutate a provider
     * profile, radius, and availability (logical OR).
     */
    private static final List<String> PROVIDER_WRITE_TIER =
            List.of("SERVICE_PROVIDER", "ADMIN", "SUPER_ADMIN");

    /**
     * Money-facing tier: settlements and earnings history additionally admit
     * {@code FINANCE_ADMIN}, who reconciles payouts (Requirement 14).
     */
    private static final List<String> PROVIDER_FINANCE_TIER =
            List.of("SERVICE_PROVIDER", "ADMIN", "SUPER_ADMIN", "FINANCE_ADMIN");

    /**
     * Profile readers: dispatch needs a provider's skills/radius/availability to offer jobs and
     * support needs it to answer customer queries, so {@code DISPATCHER} and
     * {@code SUPPORT_AGENT} are admitted for the read path only.
     */
    private static final List<String> PROFILE_READ_TIER =
            List.of("SERVICE_PROVIDER", "ADMIN", "SUPER_ADMIN", "DISPATCHER", "SUPPORT_AGENT");

    /**
     * The Admin Portal's provider management (Requirement 19.2): listing every provider and
     * suspending / reinstating one. Platform administrators only.
     */
    private static final List<String> ADMIN_TIER = List.of("ADMIN", "SUPER_ADMIN");

    private final RbacProperties rbacProperties;

    public ProviderRbacConfig(RbacProperties rbacProperties) {
        this.rbacProperties = rbacProperties;
    }

    /**
     * Registers the provider endpoint → role rules. Deliberately adds no entry for the
     * health/actuator or {@code /internal/**} surfaces — see the class Javadoc.
     */
    @PostConstruct
    public void registerEndpointRoles() {
        var rules = rbacProperties.getEndpointRoles();
        // Admin Portal provider management (Requirement 19.2). "/admin/providers/**" also matches
        // the bare "/admin/providers" list. These paths share no prefix with /providers/**, so the
        // rules below cannot shadow them or be shadowed by them.
        rules.put("GET /admin/providers/**", ADMIN_TIER);
        rules.put("PATCH /admin/providers/**", ADMIN_TIER);
        // Mutating provider self-service: profile, radius, availability, emergency availability
        // (Requirements 4.1-4.6, 4.8).
        rules.put("PUT /providers/**", PROVIDER_WRITE_TIER);
        // Settlement request and earnings history also admit Finance (Requirements 14.2, 14.5, 4.9).
        rules.put("POST /providers/*/settlements", PROVIDER_FINANCE_TIER);
        rules.put("GET /providers/*/earnings", PROVIDER_FINANCE_TIER);
        // Wallet/settlement reads sit in the same finance tier as the earnings history they
        // summarise (Requirements 14.1-14.3).
        rules.put("GET /providers/*/summary", PROVIDER_FINANCE_TIER);
        // The active-job list is operational rather than financial, so it follows the same
        // tier as the profile read that dispatch and support already rely on.
        rules.put("GET /providers/*/active-jobs", PROFILE_READ_TIER);
        rules.put("GET /providers/*/settlement-info", PROVIDER_FINANCE_TIER);
        rules.put("GET /providers/*/settlements", PROVIDER_FINANCE_TIER);
        // Profile read is additionally visible to dispatch and support.
        rules.put("GET /providers/*/profile", PROFILE_READ_TIER);
    }
}
