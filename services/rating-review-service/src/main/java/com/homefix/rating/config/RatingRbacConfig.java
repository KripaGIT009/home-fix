package com.homefix.rating.config;

import java.util.List;

import org.springframework.context.annotation.Configuration;

import com.homefix.shared.security.RbacProperties;

import jakarta.annotation.PostConstruct;

/**
 * Populates the shared {@link RbacProperties} allow-list for the Rating &amp; Review Service.
 *
 * <p>Without these rules the shared {@code RbacEnforcementFilter} finds no matching pattern and
 * falls through, which means any valid token — a CUSTOMER token included — could approve or remove
 * a flagged review.
 *
 * <p><strong>Why {@code @PostConstruct} and not {@code ApplicationReadyEvent}.</strong> The two
 * pre-existing RBAC configs in this platform registered their rules on
 * {@code ApplicationReadyEvent}. That leaves a window: the embedded servlet container is started
 * during context refresh and begins accepting connections <em>before</em> that event fires, so any
 * request arriving in between is evaluated against an empty allow-list and passes straight through
 * on nothing more than a valid token. Registering from {@code @PostConstruct} makes the rules part
 * of this bean's initialisation, so they are in place before the context finishes refreshing and
 * therefore before the first request can be served.
 *
 * <p><strong>Ordering matters.</strong> {@code RbacEnforcementFilter} takes the <em>first</em>
 * matching entry and stops, so the specific moderation paths are declared ahead of the broader
 * submission patterns. {@code POST /reviews} is a bare, wildcard-free pattern and so cannot itself
 * swallow {@code /reviews/customer} or {@code /reviews/{id}/approval}, but the specific paths are
 * declared first regardless, as defence against someone later widening it to
 * {@code POST /reviews/**}.
 *
 * <p><strong>Roles are a logical OR.</strong> Holding any one of the listed roles is sufficient.
 *
 * <p><strong>Deliberately unruled.</strong> {@code /health/**}, {@code /actuator/**},
 * {@code /metrics} and {@code /prometheus} get no rule: they are {@code permitAll()} in
 * {@code WebSecurityConfig}, and because this filter runs <em>inside</em> the security chain a
 * matching rule would turn those public probes into 401s.
 *
 * <p>Role membership is the coarse gate only. A review must additionally be attributed to the
 * caller: {@code ReviewService} rejects a submission whose reviewer is not the principal invited by
 * the booking's review prompt with a 403.
 */
@Configuration
public class RatingRbacConfig {

    /** Staff who may moderate user-generated content. */
    private static final List<String> MODERATION_TIER =
            List.of("ADMIN", "SUPER_ADMIN", "SUPPORT_AGENT");

    /** A customer reviewing the provider who served them. */
    private static final List<String> CUSTOMER_REVIEWER =
            List.of("CUSTOMER", "ADMIN", "SUPER_ADMIN", "SUPPORT_AGENT");

    /** A provider rating the customer they served (Requirement 15.10). */
    private static final List<String> PROVIDER_REVIEWER =
            List.of("SERVICE_PROVIDER", "ADMIN", "SUPER_ADMIN", "SUPPORT_AGENT");

    private final RbacProperties rbacProperties;

    public RatingRbacConfig(RbacProperties rbacProperties) {
        this.rbacProperties = rbacProperties;
    }

    /** Registers the endpoint allow-list. Public so unit tests can drive it directly. */
    @PostConstruct
    public void registerEndpointRoles() {
        var rules = rbacProperties.getEndpointRoles();
        // Admin Portal moderation (19.2): the review list and publish/remove decisions. "/**" also
        // matches the bare "/admin/reviews" list path.
        rules.put("GET /admin/reviews/**", MODERATION_TIER);
        rules.put("POST /admin/reviews/**", MODERATION_TIER);
        // Most specific first: Admin/Support moderation of flagged or offending reviews (15.5, 15.9).
        rules.put("POST /reviews/*/approval", MODERATION_TIER);
        rules.put("POST /reviews/*/removal", MODERATION_TIER);
        // Provider -> customer rating (15.10).
        rules.put("POST /reviews/customer", PROVIDER_REVIEWER);
        // Customer -> provider review (15.2, 15.3).
        rules.put("POST /reviews", CUSTOMER_REVIEWER);
    }
}
